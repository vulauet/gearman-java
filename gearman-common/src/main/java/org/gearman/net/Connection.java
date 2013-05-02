package org.gearman.net;

import com.google.common.primitives.Ints;
import org.gearman.common.packets.Packet;
import org.gearman.common.packets.PacketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Arrays;


public class Connection {
    protected Socket socket;
    protected String hostname;
    protected int port;
    private Logger LOG = LoggerFactory.getLogger(Connection.class);

    public Connection()
    {	}


    public Connection(String hostname, int port) throws IOException
    {
        this.hostname = hostname;
        this.port = port;

        socket = new Socket(hostname, port);
    }


    public Connection(Socket socket)
    {
        this.socket = socket;
    }


    public void sendPacket(Packet p) throws IOException
    {
        socket.getOutputStream().write(p.toByteArray());
    }

    public void close() throws IOException
    {
        socket.close();
    }

    public String toString()
    {
        return String.format("%s:%d", this.hostname, this.port);
    }


    public Packet getNextPacket() throws IOException
    {
        int messagesize = -1;

        // Initialize to 12 bytes (header only), and resize later as needed
        byte[] header = new byte[12];
        byte[] packetBytes;

        InputStream is = socket.getInputStream();
        try {

            int numbytes = is.read(header, 0, 12);

            if(numbytes == 12)
            {
                // Check byte count
                byte[] sizebytes = Arrays.copyOfRange(header, 8, 12);

                messagesize = Ints.fromByteArray(sizebytes);

                if (messagesize > 0)
                {
                    // Grow packet buffer to fit data
                    packetBytes = Arrays.copyOf(header, 12 + messagesize);
                } else {
                    packetBytes = header;
                }

                return PacketFactory.packetFromBytes(packetBytes);

            } else if(numbytes == -1) {

            }
        } catch (Exception e) {
            LOG.error("Exception reading data: ", e);
            e.printStackTrace();
            return null;
        }

        return null;
    }
}



