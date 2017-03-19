package whs.bot.desktopnxtadapter;

import java.nio.ByteBuffer;

public abstract class Subsystem {
	public abstract String getName();
	public abstract int getNumericType();
	
	protected Driver driver;
	protected Robot robot;
	
	protected int id;
	
	public Subsystem(Robot r, int id) {
		this.robot = r;
		this.id = id;
	}
	
	public Driver getDriver() {
		return driver;
	}
	
	// returns true on success
	public boolean bind(Driver d) {
		this.robot.log(d.getName() + " attempted to bind " + getName());
		if(driver != null) {
			return false;
		} else {
			driver = d;
            robot.driverChanged(this);
			return true;
		}
	}
	public void unbind(Driver d) {
		if(driver == d) {
			driver = null;
            robot.driverChanged(this);
			this.robot.log(d.getName() + " unbound " + getName());
		}
	}
	public abstract void update(ByteBuffer inBuffer);
	public boolean hasUpdate() {
		return false;
	}
	public void writeUpdate(ByteBuffer buf) {
		
	}
	public int getId() {
		return id;
	}
    public void destroy() {

    }
}
