package org.gearman.server;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.annotation.Metered;
import com.yammer.metrics.annotation.Timed;
import com.yammer.metrics.core.Counter;
import org.gearman.common.packets.Packet;
import org.gearman.common.packets.request.GetStatus;
import org.gearman.common.packets.request.SubmitJob;
import org.gearman.common.packets.response.NoJob;
import org.gearman.common.packets.response.StatusRes;
import org.gearman.common.packets.response.WorkResponse;
import org.gearman.common.packets.response.WorkStatus;
import org.gearman.constants.GearmanConstants;
import org.gearman.constants.JobPriority;
import org.gearman.constants.PacketType;
import org.gearman.server.core.RunnableJob;
import org.gearman.server.persistence.PersistenceEngine;
import org.gearman.server.util.EqualsLock;
import org.jboss.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 *
 */
public class JobStore {
    public static final Date timeStarted = new Date();

    private static Logger LOG = LoggerFactory.getLogger(JobStore.class);
    // Job Queues: Function Name <--> JobQueue
    private final ConcurrentHashMap<String, JobQueue> jobQueues = new ConcurrentHashMap<>();

    // Workers: Worker <--> Abilities
    private final ConcurrentHashMap<Channel, Set<String>> workers = new ConcurrentHashMap<>();

    // Active jobs Worker <--> Job
    private final ConcurrentHashMap<Channel, Job> activeJobs = new ConcurrentHashMap<>();

    private final EqualsLock lock = new EqualsLock();
    private final PersistenceEngine persistenceEngine;

    // Metrics counters, FunctionName <--> Counter
    private final ConcurrentHashMap<String, Counter> pendingCounters = new ConcurrentHashMap<>();

    private final Counter pendingJobs = Metrics.newCounter(JobStore.class, "pending-jobs");
    private final Counter queuedJobs = Metrics.newCounter(JobStore.class, "queued-jobs");
    private final Counter completedJobs = Metrics.newCounter(JobStore.class, "completed-jobs");

    public JobStore(PersistenceEngine persistenceEngine)
    {
        this.persistenceEngine = persistenceEngine;
    }

    public synchronized Job getByJobHandle(String jobHandle){
        return persistenceEngine.findJobByHandle(jobHandle);
    }

    public void registerWorker(String funcName, Channel worker)
    {
        if(!this.workers.containsKey(worker))
        {
            this.workers.put(worker, new HashSet<String>());
        }
        this.workers.get(worker).add(funcName);

        getJobQueue(funcName).addWorker(worker);
    }

    public void unregisterWorker(String funcName, Channel worker)
    {
        if(this.workers.containsKey(worker))
        {
            this.workers.get(worker).remove(funcName);
        }

        getJobQueue(funcName).removeWorker(worker);
    }

    public void sleepingWorker(Channel worker)
    {
        if(workers.containsKey(worker))
        {
            for(String funcName : workers.get(worker))
            {
                getJobQueue(funcName).setWorkerAsleep(worker);
            }
        }
    }

    @Timed
    @Metered
    public void nextJobForWorker(Channel worker, boolean uniqueID)
    {
        boolean foundJob = false;

        if(workers.containsKey(worker))
        {
            for(String functionName : workers.get(worker))
            {
                final JobQueue jobQueue = getJobQueue(functionName);
                final Job job = jobQueue.nextJob();
                jobQueue.setWorkerAwake(worker);

                if (job != null)
                {
                    activeJobs.put(worker, job);
                    job.setState(Job.JobState.WORKING);
                    job.setWorker(worker);

                    Packet packet;

                    if(uniqueID)
                    {
                        packet = job.createJobAssignUniqPacket();
                    } else {
                        packet = job.createJobAssignPacket();
                    }

                    foundJob = true;

                    try {
                        worker.write(packet);
                    } catch (Exception e) {
                            LOG.error("Unable to write to worker. Re-enqueing job.");
                            try {
                                reEnqueueJob(job);
                            } catch (IllegalJobStateTransitionException ee) {
                                LOG.error("Error re-enqueing after failed transmission: ", ee);
                            }
                    }
                }
            }
        }

        if(!foundJob) {
             worker.write(new NoJob());
        }

    }

    public synchronized void removeJob(Job job)
    {
        pendingJobs.dec();
        getJobQueue(job.getFunctionName()).remove(job);

        if(job.isBackground() && persistenceEngine != null)
        {
            try {
                persistenceEngine.delete(job);
            } catch (Exception e) {
                // TODO: be more specific
                LOG.debug("Can't remove job from persistence engine: " + e.toString());
                e.printStackTrace();
            }
        }
    }

    @Timed
    @Metered
    public final void createJob(SubmitJob packet, Channel creator) {
        //creator.addDisconnectListener(this);
        String funcName = packet.getFunctionName();
        String uniqueID = packet.getUniqueId();
        byte[] data = packet.getData();
        JobPriority priority = packet.getPriority();
        boolean isBackground = packet.isBackground();

        pendingJobs.inc();
        queuedJobs.inc();

        JobQueue jobQueue = getJobQueue(funcName);

        if(uniqueID.isEmpty()) {
            do {
                uniqueID = new String(UUID.randomUUID().toString().getBytes(GearmanConstants.CHARSET));
            } while(jobQueue.uniqueIdInUse(uniqueID));
        }

        final Integer key = uniqueID.hashCode();
        this.lock.lock(key);
        try {

            // Make sure only one thread attempts to add a job with this uID at once
            if(jobQueue.uniqueIdInUse(uniqueID)) {

                final Job job = jobQueue.getJobByUniqueId(uniqueID);
                if(job!=null) {
                    synchronized(job) {
                        // If the job is not background, add creator to listener set and send JOB_CREATED packet
                        if(!isBackground) job.addClient(creator);
                        creator.write(job.createJobCreatedPacket());
                        return;
                    }
                }
            }

            long timeToRun = -1;

            if(packet.getType() == PacketType.SUBMIT_JOB_EPOCH)
            {
                timeToRun = packet.getEpoch();
            }

            final Job job = new Job(funcName, uniqueID, data, priority, isBackground, timeToRun, creator);

            if(!jobQueue.enqueue(job))
            {
                LOG.error("Unable to enqueue job");
            } else {
                creator.write(job.createJobCreatedPacket());
                // Notify any workers if this job is ready to run so it gets picked up quickly
                if(job.isReady())
                    jobQueue.notifyWorkers();

            }
        } finally {
            // Always unlock lock
            this.lock.unlock(key);
        }
    }

    public final void reEnqueueJob(Job job) throws IllegalJobStateTransitionException
    {
        Job.JobState previousState = job.getState();
        job.setState(Job.JobState.QUEUED);
        final JobQueue jobQueue = getJobQueue(job.getFunctionName());
        switch(previousState) {
            case QUEUED:
                // Do nothing
                break;
            case WORKING:
                // Requeue
                LOG.debug("Re-enqueing job " + job.toString());
                final boolean value = jobQueue.enqueue(job);
                assert value;
                break;
            case COMPLETE:
                throw new IllegalJobStateTransitionException("Jobs should not transition from complete to queued.");
                // should never go from COMPLETE to QUEUED
        }
    }


    @Timed
    @Metered
    public synchronized void workComplete(WorkResponse packet, Channel worker)
    {
        Job job = activeJobs.remove(worker); //getByJobHandle(packet.getJobHandle());

        if(job != null)
        {
            Set<Channel> clients = job.getClients();

            for(Channel client : clients) {
                client.write(packet);
            }

            job.complete();
            removeJob(job);
            completedJobs.inc();
        }
    }


    public void checkJobStatus(GetStatus getStatus, Channel channel)
    {
        String jobHandle = getStatus.jobHandle.get();
        Job job = getByJobHandle(jobHandle);
        StatusRes response;

        if(job != null)
        {
           response = (StatusRes)job.createStatusResponsePacket();
        } else {
           response = new StatusRes(jobHandle, false, false, 0, 0);
        }

        channel.write(response);

    }

    public void updateJobStatus(WorkStatus workStatus)
    {
        Job job = getByJobHandle(workStatus.jobHandle.get());
        job.setStatus(workStatus.completenumerator, workStatus.completedenominator);
        persistenceEngine.write(job);
    }

    public synchronized void channelDisconnected(Channel channel)
    {
        // Remove from any worker lists
        if(workers.containsKey(channel))
        {
            for(String jobQueueName : workers.get(channel))
            {
                getJobQueue(jobQueueName).removeWorker(channel);
            }

            workers.remove(channel);

            if(activeJobs.containsKey(channel))
            {
                Job job = activeJobs.remove(channel);
                JobQueue jobQueue = getJobQueue(job.getFunctionName());
                Job.JobAction action = job.disconnectClient(channel);
                switch (action)
                {
                    case REENQUEUE:
                        try {
                            reEnqueueJob(job);
                        } catch (IllegalJobStateTransitionException e) {
                            LOG.error("Unable to re-enqueue job: " + e.toString());
                        }
                        break;
                    case MARKCOMPLETE:
                        jobQueue.remove(job);
                        break;
                    case DONOTHING:
                    default:
                        break;
                }
            }
        }

    }


    public void loadAllJobs()
    {
        if(persistenceEngine != null)
        {
            Collection<RunnableJob> jobs = null;

            try {
                jobs = persistenceEngine.readAll();
            } catch (Exception ex) {
                // TODO LOG
                LOG.debug("Error loading persistent data: " + ex.toString());
            }

            if(jobs==null) return;
            for(RunnableJob job : jobs) {
                String functionName = job.getFunctionName();
                if(functionName == null) {
                    // TODO log
                    LOG.debug("Error queueing job: functionName is null");
                } else {
                    JobQueue jobQueue = getJobQueue(functionName);
                    try {
                        jobQueue.enqueue(job);
                        pendingJobs.inc();
                    } catch (Exception e) {
                        LOG.error(e.toString());
                    }
                }
            }

            LOG.info("Loaded " + jobs.size() + " jobs from persistent storage.");
        }
    }


    public final JobQueue getJobQueue(String name) {
        Integer key = name.hashCode();
        try {
            lock.lock(key);

            JobQueue jobQueue = jobQueues.get(name);

            if(jobQueue==null)
            {
                jobQueue = new JobQueue(name, persistenceEngine);
                this.jobQueues.put(name, jobQueue);
            }

            return jobQueue;
        } finally {
            lock.unlock(key);
        }
    }

    public ConcurrentHashMap<String, JobQueue> getJobQueues() {
        return jobQueues;
    }

    public PersistenceEngine getPersistenceEngine()
    {
        return persistenceEngine;
    }

    public Counter getPendingJobs() {
        return pendingJobs;
    }

    public Counter getCompletedJobs() {
        return completedJobs;
    }

    public Counter getQueuedJobs() {
        return queuedJobs;
    }

    public Integer getWorkerCount() {
        return workers.keySet().size();
    }
}
