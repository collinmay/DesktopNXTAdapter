package whs.bot.desktopnxtadapter;

import javax.bluetooth.ServiceRecord;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by misson20000 on 3/18/17.
 */
public class NXTStub implements Battery, NXTDisconnectionListener {
    private final ServiceRecord serviceRecord;
    private final Thread thread;
    private NXT actualNXT;
    private List<ConnectionStateChangeListener> listeners = new ArrayList<>();
    private String name;
    private Robot robot;
    private final Object connectionLock = new Object();
    private NXTMotor[] motors;
    private static int port = 40560;

    public NXTStub(ServiceRecord serviceRecord, Robot robot) {
        System.out.println("Discovered NXT, addr " + serviceRecord.getHostDevice().getBluetoothAddress());
        this.serviceRecord = serviceRecord;
        this.robot = robot;

        motors = new NXTMotor[3];
        for(int i = 0; i < motors.length; i++) {
            motors[i] = new NXTMotor(this, NXTMotor.MotorPort.values()[i]);
        }

        try {
            this.name = serviceRecord.getHostDevice().getFriendlyName(true);
            System.out.println("Name: " + name);
        } catch (IOException e) {
        }
        this.thread = new Thread(() -> {
            while(true) {
                if(actualNXT == null) {
                    try {
                        connect();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
                synchronized (connectionLock) {
                    if(actualNXT != null) {
                        try {
                            connectionLock.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        });
        this.thread.start();

        Thread musicThread = new Thread(() -> {
            final int myPort = port;
            port++;
            ServerSocket srv = null;
            try {
                srv = new ServerSocket(myPort);
            } catch (IOException e) {
                e.printStackTrace();
            }
            while(true) {
                try {
                    System.out.println("Music for " + name + " on " + myPort);
                    Socket musC = null;
                    do {
                        musC = srv.accept();
                    } while (musC == null);
                    InputStream in = musC.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(Integer.parseInt(line));
                        actualNXT.playTone(Integer.parseInt(line), 5000);
                    }

                    actualNXT.playTone(0, 10);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, "Music thread for " + name);
        musicThread.start();
    }

    private void connect() throws IOException {
        StreamConnection c = (StreamConnection) Connector.open(serviceRecord.getConnectionURL(0, false));
        actualNXT = new NXT(this, robot, serviceRecord.getHostDevice(), c);
        synchronized(actualNXT) {
            if (actualNXT.isAlive()) {
                listeners.forEach((l) -> l.nxtConnected(this, actualNXT));
            }
        }
    }

    public boolean isOnline() {
        return actualNXT != null && actualNXT.isAlive();
    }

    @Override
    public int getBatteryLevel() {
        return isOnline() ? actualNXT.getBatteryLevel() : 0;
    }

    @Override
    public String getName() {
        return name;
    }

    public NXT getNXT() {
        return actualNXT;
    }

    public void addConnectionListener(ConnectionStateChangeListener listener) {
        listeners.add(listener);
    }

    public void removeConnectionListener(ConnectionStateChangeListener listener) {
        listeners.remove(listener);
    }

    public NXTMotor getMotor(NXTMotor.MotorPort port) {
        return motors[port.ordinal()];
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public void nxtDisconnected(NXTStub stub, NXT actualNXT) {
        synchronized(connectionLock) {
            actualNXT.ensureDisconnected();
            this.actualNXT = null;
        }
        listeners.forEach((l) -> l.nxtDisconnected(stub, actualNXT));
        synchronized (connectionLock) {
            connectionLock.notifyAll();
        }
    }

    public interface ConnectionStateChangeListener extends NXTDisconnectionListener {
        public void nxtConnected(NXTStub stub, NXT nxt);

        @Override
        public void nxtDisconnected(NXTStub stub, NXT actualNXT);
    }
}
