package whs.bot.desktopnxtadapter;

import javax.bluetooth.RemoteDevice;
import javax.microedition.io.StreamConnection;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;


/*
 * Multithreading?
 *  - battery thread
 *     checks battery life
 *  - output thread
 *     outputs motor powers
 *  locks:
 *  - NXT monitor
 *    - output buffer
 */


public class NXT implements Battery {
    private boolean sleeping;
    private RemoteDevice dev;
    private StreamConnection connection;
    private DataOutputStream outStream;
    private DataInputStream inStream;

    private Robot robot;
    private Thread thread;

    private NXTMotor[] motors;

    private short battery;

    private ByteBuffer out = ByteBuffer.allocate(66);
    private ByteBuffer in = ByteBuffer.allocate(66);
    private long nextUpdate;

    private ConnectionStatus status = ConnectionStatus.DISCONNECTED;

    private List<NXTDisconnectionListener> disconnectionListeners;

    private boolean alive = true;

    private NXTStub stub;
    private String changeBrickName;

    public NXT(final NXTStub stub, final Robot rob, final RemoteDevice dev, final StreamConnection connection) throws IOException {
        System.out.println("Connected NXT");
        this.dev = dev;
        this.connection = connection;
        this.robot = rob;
        this.disconnectionListeners = new ArrayList<>();
        this.stub = stub;
        this.motors = new NXTMotor[] {
                stub.getMotor(NXTMotor.MotorPort.A),
                stub.getMotor(NXTMotor.MotorPort.B),
                stub.getMotor(NXTMotor.MotorPort.C)
        };

        this.disconnectionListeners.add(stub);

        this.outStream = connection.openDataOutputStream();
        this.inStream = connection.openDataInputStream();

        final byte[] prefix = new byte[] { (byte) 0x80, 0x04 };
        final byte[] postfix = new byte[] { 0x07, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00 };

        thread = new Thread(() -> {
            for (int i = 0; i < motors.length; i++) {
                motors[i].setOutPower(0);
            }

            nextUpdate = System.currentTimeMillis() + 40;
            long nextBattery = nextUpdate;

            out.clear();
            out.position(2);
            out.order(ByteOrder.LITTLE_ENDIAN);

            in.clear();
            in.order(ByteOrder.LITTLE_ENDIAN);

            try {
                playTone(60, 500);
                playTone(64, 500);
                playTone(67, 500);
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                while (alive) {
                    try {
                        synchronized(this) {
                            sleeping = true;
                        }
                        Thread.sleep(Math.max(0, System.currentTimeMillis() - nextUpdate));
                    } catch (InterruptedException ignored) {
                    }
                    Thread.interrupted(); // clear interrupt
                    synchronized(this) {
                        Thread.interrupted(); // clear interrupt again for good measure
                        sleeping = false;
                    }

                    if (System.currentTimeMillis() > nextUpdate) {
                        if (System.currentTimeMillis() > nextBattery) {
                            synchronized(this) {
                                out.put((byte) 0x00);
                                out.put((byte) 0x0B);
                                writeCmd();
                                int len = readCmd();
                                in.mark();
                                if (in.get() != 0x02) {
                                    in.reset();
                                    in.position(in.position() + len);
                                    continue;
                                }
                                if (in.get() != 0x0B) {
                                    in.reset();
                                    in.position(in.position() + len);
                                    continue;
                                }
                                Status status = getStatus((char) in.get());
                                if (status != Status.SUCCESS) {
                                    rob.log("error checking battery: " + status.getMessage());
                                    in.reset();
                                    in.position(in.position() + len);
                                } else {
                                    battery = in.getShort();
                                    nextBattery += 500;
                                }
                                in.reset();
                                in.position(in.position() + len);
                            }
                        } else {
                            for (int i = 0; i < motors.length; i++) {
                                NXTMotor m = motors[i];
                                if (m.getInPower() != m.getOutPower()) {
                                    m.setOutPower(m.getInPower());
                                    synchronized(this) {
                                        out.put(prefix);
                                        out.put((byte) m.getPort().ordinal());
                                        out.put((byte) (m.getOutPower() * 100));
                                        out.put(postfix);
                                        writeCmd();
                                    }
                                }
                            }
                            if (changeBrickName != null) {
                                synchronized(this) {
                                    out.put((byte) 0x81);
                                    out.put((byte) 0x98);
                                    out.put(changeBrickName.getBytes());
                                    for (int i = 16 - changeBrickName.length(); i > 0; i--) {
                                        out.put((byte) 0);
                                    }
                                    writeCmd();
                                }
                                stub.setName(changeBrickName);
                                changeBrickName = null;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                }
            } finally {
                alive = false;
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                disconnectionListeners.forEach((l) -> l.nxtDisconnected(stub, this));
            }
        });

        thread.start();
        ////                                     direct motor port  power               mode  reg   turn  state tacho limit
        //data = new byte[] { 0x0c, 0x00, (byte) 0x80,  0x04, 0x02, (byte) (left*100),  0x07, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00,
        //    			   	 0x0c, 0x00, (byte) 0x80,  0x04, 0x01, (byte) (right*100), 0x07, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00 };
    }

    private static double hertz(int midi) {
        return 440.0 * Math.pow(Math.pow(2, 1.0 / 12.0), midi - 69);
    }

    public synchronized void playTone(int midi, int duration) throws IOException {
        out.put((byte) 0x80);
        out.put((byte) 0x03);
        out.putShort(midi == 0 ? 0 : (short) hertz(midi));
        out.putShort((short) duration);
        writeCmd();
    }

    private void writeCmd() throws IOException {
        out.flip();
        int len = out.remaining() - 2;
        out.putShort((short) len);
        out.position(0);
        outStream.write(out.array(), out.arrayOffset() + out.position(), out.remaining());
        out.clear();
        out.position(2);
        nextUpdate = System.currentTimeMillis() + 20;
        outStream.flush();
    }

    private int readCmd() throws IOException {
        in.position(in.position() + inStream.read(in.array(), in.arrayOffset() + in.position(), in.remaining()));
        in.flip();
        return in.getShort();
    }

    public void kill() {
        alive = false;
        try {
            connection.close();
        } catch (IOException e) {
        }
    }

    public String getName() {
        return "NXT " + stub.getName();
    }

    public synchronized void setMotor(NXTMotor.MotorPort port, double power) {
        if(sleeping) {
            this.thread.interrupt();
        }
    }

    public int getBatteryLevel() {
        return battery;
    }

    public ConnectionStatus getConnectionStatus() {
        return status;
    }

    private Status getStatus(char stat) {
        switch (stat) {
        case 0:
            return Status.SUCCESS;
        case 0x20:
            return Status.PENDING_COMM;
        case 0x40:
            return Status.MBOX_QUEUE_EMPTY;
        case 0xBD:
            return Status.REQUEST_FAILED;
        case 0xBE:
            return Status.UNKNOWN_OPCODE;
        case 0xBF:
            return Status.INSANE_PACKET;
        case 0xC0:
            return Status.OUT_OF_RANGE;
        case 0xDD:
            return Status.COMM_BUS_ERROR;
        case 0xDE:
            return Status.OOM_COMM_BUFFER;
        case 0xDF:
            return Status.CHANCON_INVALID;
        case 0xE0:
            return Status.CHANCON_BUSY;
        case 0xEC:
            return Status.NO_PROGRAM;
        case 0xED:
            return Status.BAD_SIZE;
        case 0xEE:
            return Status.BAD_MBOX_QUEUE;
        case 0xEF:
            return Status.BAD_STRUCTURE;
        case 0xF0:
            return Status.BAD_IO;
        case 0xFB:
            return Status.OOM;
        case 0xFF:
            return Status.BAD_ARG;
        default:
            return Status.UNKNOWN;
        }
    }

    public void changeBrickName(String s) {
        this.changeBrickName = s;
        this.thread.interrupt();
    }

    public NXTStub getStub() {
        return stub;
    }

    public boolean isAlive() {
        return alive;
    }

    public void ensureDisconnected() {
        try {
            inStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            outStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            connection.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public enum Status {
        SUCCESS("success"), PENDING_COMM("pending communication transaction"), MBOX_QUEUE_EMPTY("mailbox queue empty"), REQUEST_FAILED("request failed (i.e. specified file not found)"), UNKNOWN_OPCODE("unknown command opcode"), INSANE_PACKET("insane packet"), OUT_OF_RANGE("data contains out-of-range values"), COMM_BUS_ERROR("communication bus error"), OOM_COMM_BUFFER("no free memory in communication buffer"), CHANCON_INVALID("specified channel/connection is not valid"), CHANCON_BUSY("specified channel/connection is not configured or busy"), NO_PROGRAM("no active program"), BAD_SIZE("illegal size speicifed"), BAD_MBOX_QUEUE("invalid mailbox queue ID specified"), BAD_STRUCTURE("attempted to access invalid field of a structure"), BAD_IO("bad input or output specified"), OOM("insufficient memory available"), BAD_ARG("bad arguments"), UNKNOWN("bad status byte received");

        private String message;

        Status(String msg) {
            this.message = msg;
        }

        public String getMessage() {
            return message;
        }
    }

    public enum ConnectionStatus {
        DISCONNECTED("Disconnected"), CONNECTING("Connecting..."), RECONNECTING("Reconnecting..."), CONNECTED("Connected");

        private String msg;

        ConnectionStatus(String msg) {
            this.msg = msg;
        }

        public String toString() {
            return msg;
        }
    }

    public interface StateChangeListener {
        public void stateChanged(NXT nxt);
    }
}
