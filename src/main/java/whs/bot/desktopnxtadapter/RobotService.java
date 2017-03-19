package whs.bot.desktopnxtadapter;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.DeviceClass;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.DiscoveryListener;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.RemoteDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.UUID;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by misson20000 on 3/4/16.
 */
public class RobotService {
    private boolean isRunning;
    private Robot robot;
    private Thread multicastThread;
    private Thread serverThread;
    private List<NXTStub> nxtStubs;
    private List<NXTStub.ConnectionStateChangeListener> nxtListeners;
    private LocalDevice bluetoothAdapter;
    private Thread btThread;
    private Set<String> alreadyStubbed = new HashSet<>();

    public void start() throws BluetoothStateException {
        if(!isRunning) {
            isRunning = true;

            robot = new Robot();

            nxtStubs = new ArrayList<>();
            nxtListeners = new ArrayList<>();

            bluetoothAdapter = LocalDevice.getLocalDevice();

            btThread = new Thread(new BTThread());
            btThread.start();

            multicastThread = new Thread(new MulticastThread());
            multicastThread.start();

            serverThread = new Thread(new ServerThread());
            serverThread.start();
        }
    }

    public Robot getRobot() {
        return robot;
    }

    public List<NXTStub> getNxtStubs() {
        return nxtStubs;
    }

    private class ServerThread implements Runnable {
        @Override
        public void run() {
            while(isRunning) {
                try {
                    ServerSocketChannel sock = ServerSocketChannel.open();
                    try {
                        sock.socket().bind(new InetSocketAddress(25600));
                        while(isRunning) {
                            SocketChannel client = sock.accept();
                            robot.addDriver(new Driver(robot, client));
                        }
                    } catch(IOException e) {

                    } finally {
                        sock.close();
                    }
                } catch(IOException e) {

                }
            }
        }
    }

    private class MulticastThread implements Runnable {
        @Override
        public void run() {
            MulticastSocket sock = null;
            try {
                sock = new MulticastSocket(25601);
                sock.joinGroup(InetAddress.getByName("238.160.102.2"));

                byte[] inBuffer = new byte[11];
                DatagramPacket inPacket = new DatagramPacket(inBuffer, 11);

                byte[] outBuffer = "Desktop NXT Adapter".getBytes();
                DatagramPacket outPacket = new DatagramPacket(outBuffer, outBuffer.length);
                while(isRunning) {
                    sock.receive(inPacket);
                    if (new String(inBuffer).equals("find robots")) {
                        outPacket.setSocketAddress(inPacket.getSocketAddress());
                        outPacket.setPort(inPacket.getPort());
                        sock.send(outPacket);
                    }
                }
            } catch (IOException e) {

            } finally {
                if (sock != null) {
                    sock.close();
                }
            }
        }
    }

    private class BTThread implements Runnable, DiscoveryListener {
        public final UUID[] uuidSet = new UUID[] {
                //new UUID("0000110100001000800000805F9B34FB", false),
                new UUID("1101", true)
        };

        private int outstandingServiceSearches = 0;

        @Override
        public void run() {
            System.out.println("Inquiry");
            DiscoveryAgent agent = bluetoothAdapter.getDiscoveryAgent();
            try {
                agent.startInquiry(DiscoveryAgent.GIAC, this);
            } catch (BluetoothStateException e) {
                e.printStackTrace();
            }
        }

        @Override
        public synchronized void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {
            if(!alreadyStubbed.contains(btDevice.getBluetoothAddress())) {
                System.out.println("Found device");
                System.out.println("  major: " + cod.getMajorDeviceClass());
                System.out.println("  minor: " + cod.getMajorDeviceClass());
                System.out.println("  address: " + btDevice.getBluetoothAddress());
                try {
                    System.out.println("  name: " + btDevice.getFriendlyName(true));
                } catch (IOException e) {
                    e.printStackTrace();
                }

                try {
                    System.out.println("Launching service search");
                    bluetoothAdapter.getDiscoveryAgent().searchServices(null, uuidSet, btDevice, this);
                    outstandingServiceSearches++;
                } catch (BluetoothStateException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
            if(servRecord == null) {
                System.out.println("Found no services");
            } else {
                System.out.println("Found " + servRecord.length + " services");
            }
            if(servRecord != null && servRecord.length > 0 && !alreadyStubbed.contains(servRecord[0].getHostDevice().getBluetoothAddress())) {
                robot.addNXTStub(new NXTStub(servRecord[0], robot));
                alreadyStubbed.add(servRecord[0].getHostDevice().getBluetoothAddress());
            }
        }

        @Override
        public void serviceSearchCompleted(int transID, int respCode) {
            System.out.println("Service search complete");
            outstandingServiceSearches--;
            if(outstandingServiceSearches <= 0) {
                outstandingServiceSearches = 0;
                run();
            }
        }

        @Override
        public void inquiryCompleted(int discType) {
            if(outstandingServiceSearches <= 0) {
                outstandingServiceSearches = 0;
                run();
            }
        }
    }
}
