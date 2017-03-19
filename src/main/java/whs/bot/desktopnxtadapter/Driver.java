package whs.bot.desktopnxtadapter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Driver {
	private SocketChannel sock;
	private Thread inputThread;
	private Thread outputThread;
	private ByteBuffer headBuffer = ByteBuffer.allocate(5);
	private ByteBuffer outBuffer = ByteBuffer.allocate(1024);
	private String name;
	private boolean alive;
	private Robot robot;
	private List<Subsystem> boundSubsystems;
	private Queue<DriverJob> jobQueue;

	public Driver(Robot robot, SocketChannel client) {
		this.sock = client;
		this.alive = true;
		this.robot = robot;
		this.inputThread = new Thread(new InputThread());
		this.inputThread.start();
		this.outputThread = new Thread(new OutputThread());
        this.outputThread.start();
		this.boundSubsystems = new LinkedList<>();
        this.jobQueue = new ConcurrentLinkedQueue<>();
	}
	
	private synchronized void writePacket(int type, ByteBuffer buf) throws IOException {
		buf.flip();
		if(type != 1) {
		}
		this.headBuffer.putShort((short) buf.limit());
		this.headBuffer.put((byte) type);
		this.headBuffer.flip();

		while(this.headBuffer.hasRemaining()) { this.sock.write(this.headBuffer); }
		while(            buf.hasRemaining()) { this.sock.write(buf);             }

		this.headBuffer.clear();
		buf.clear();
	}

    public void queueJob(DriverJob j) {
        jobQueue.add(j);
    }

    public InetAddress getAddress() {
        return sock.socket().getInetAddress();
    }

	private class OutputThread implements Runnable {
		@Override
		public void run() {
			
			long nextBatteryUpdate = System.currentTimeMillis() + 250;
			
			try {
				while(alive) {
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {}

                    while(!jobQueue.isEmpty()) {
                        synchronized(Driver.this) {
                            jobQueue.poll().run(Driver.this);
                        }
                    }

					if(System.currentTimeMillis() > nextBatteryUpdate) {
						synchronized(Driver.this) {
							nextBatteryUpdate+= 250;
							List<Battery> batteries = robot.getBatteries();
							outBuffer.putShort((short) batteries.size());
							for(Iterator<Battery> i = batteries.iterator(); i.hasNext();) {
								outBuffer.putInt(i.next().getBatteryLevel());
							}
							writePacket(0x05, outBuffer);
						}
					}
					for(Iterator<Subsystem> i = robot.getSubsystems().iterator(); i.hasNext();) {
						Subsystem sub = i.next();
						if(sub.hasUpdate()) {
							log("writing update..."); // this is broken
							synchronized(Driver.this) {
								outBuffer.put((byte) sub.getNumericType());
								outBuffer.putShort((short) sub.getId());
								sub.writeUpdate(outBuffer);
								writePacket(0x06, outBuffer);
								log("written");
							}
						}
					}
				}
			} catch(IOException e) {
				e.printStackTrace();
				alive = false;
			} finally {
				robot.removeDriver(Driver.this);
				releaseSubsystems();
			}
		}
	}
	
	private class InputThread implements Runnable {
		@Override
		public void run() {
			ByteBuffer inBuffer = ByteBuffer.allocate(1024);
			ByteBuffer kaBuffer = ByteBuffer.allocate(16);
			int currentPacketLength = -1;
			try {
				while(alive) {
					sock.read(inBuffer);
					inBuffer.flip();
					
					if(currentPacketLength < 0) {
						if(inBuffer.remaining() >= 2) {
							currentPacketLength = inBuffer.getShort();
						}
					}
					if(currentPacketLength >= 0 && inBuffer.remaining() >= currentPacketLength + 1) {
						int type = inBuffer.get();
						int start = inBuffer.position();
						
						switch(type) {
						case 0:
							System.out.println("Invalid 0x00 packet received");
							break;
						case 1: // Keep Alive
							kaBuffer.putLong(inBuffer.getLong());
							writePacket(0x01, kaBuffer);
							break;
						case 2: // Query Subsystems
							writeSubsystemInfo();
							writeBatteryInfo();
							break;
						case 3: // Register Driver
							short length = inBuffer.getShort();
							byte[] bytes = new byte[length];
							inBuffer.get(bytes);
							setName(new String(bytes));
							log("Hello, " + name);
							break;
						case 4: { // Bind Subsystem
							short id = inBuffer.getShort();
							synchronized(Driver.this) {
								if(robot.getSubsystems().get(id).bind(Driver.this)) {
									boundSubsystems.add(robot.getSubsystems().get(id));
									outBuffer.putShort(id);
									writePacket(0x04, outBuffer);
								} else {
									outBuffer.putShort(id);
									writePacket(0x03, outBuffer);
								}
							}
						} break;
						case 5: { // Subsystem Update
							short id = inBuffer.getShort();
							robot.getSubsystems().get(id).update(inBuffer);
						} break;
						case 6: { // Unbind Subsystem
							short id = inBuffer.getShort();
							robot.getSubsystems().get(id).unbind(Driver.this);
							boundSubsystems.remove(robot.getSubsystems().get(id));
						} break;
						}
						
						inBuffer.position(start + currentPacketLength);
						currentPacketLength = -1;
					}
					
					inBuffer.compact();
				}
			} catch(IOException e) {
				e.printStackTrace();
				alive = false;
			}
		}
	}

	public synchronized void writeSubsystemInfo() throws IOException {
		List<Subsystem> subsystems = robot.getSubsystems();
		outBuffer.putShort((short) subsystems.size());
		for(Iterator<Subsystem> i = subsystems.iterator(); i.hasNext();) {
			Subsystem sub = i.next();
			outBuffer.put((byte) sub.getNumericType());
			outBuffer.putShort((short) sub.getName().length());
			outBuffer.put(sub.getName().getBytes());
			if(sub.getDriver() != null) {
				String name = sub.getDriver().getName();
				outBuffer.putShort((short) name.length());
				outBuffer.put(name.getBytes());
			} else {
				outBuffer.putShort((short) 0);
			}
		}
		writePacket(0x02, outBuffer);
	}

	public void releaseSubsystems() {
		for(Iterator<Subsystem> i = boundSubsystems.iterator(); i.hasNext();) {
			i.next().unbind(this);
		}
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public synchronized void writeBatteryInfo() throws IOException {
		List<Battery> bats = robot.getBatteries();
		outBuffer.putShort((short) bats.size());
		for(Iterator<Battery> i = bats.iterator(); i.hasNext();) {
			Battery b = i.next();
			outBuffer.putInt(10000);
			outBuffer.putInt(b.getBatteryLevel());
			outBuffer.putShort((short) b.getName().length());
			outBuffer.put(b.getName().getBytes());
		}
		writePacket(0x07, outBuffer);
	}
	
	public synchronized void log(String msg) {
		if(msg.length() > 0) {
			outBuffer.putShort((short) msg.length());
			outBuffer.put(msg.getBytes());
			try {
				writePacket(0x08, outBuffer);
			} catch (IOException e) {
				e.printStackTrace();
				alive = false;
			}
		}
	}

	public boolean isDead() {
		return !alive;
	}

    public void kill() {
        alive = false;
    }
}
