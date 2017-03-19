package whs.bot.desktopnxtadapter;

/**
 * Created by misson20000 on 3/5/16.
 */
public class NXTMotor {
    private NXTStub nxt;
    private MotorPort port;

    private double setPower;
    private double outPower;

    private boolean inUse;
    private boolean reversed;

    public NXTMotor(NXTStub nxt, MotorPort port) {
        this.nxt = nxt;
        this.port = port;
    }

    public void setPower(double power) {
        System.out.println("Set power");
        if(reversed) {
            power = -power;
        }
        this.setPower = power;
        if(nxt.isOnline()) {
            nxt.getNXT().setMotor(port, power);
        }
    }

    public double getInPower() {
        return setPower;
    }

    public double getOutPower() {
        return outPower;
    }

    public void setOutPower(double p) {
        this.outPower = p;
    }

    public boolean isOnline() {
        return nxt.isOnline();
    }

    public boolean inUse() {
        return inUse;
    }

    public void setInUse(boolean inUse) {
        this.inUse = inUse;
    }

    public NXTStub getStub() {
        return nxt;
    }

    public MotorPort getPort() {
        return port;
    }

    public void setReversed(boolean reversed) {
        this.reversed = reversed;
    }

    public enum MotorPort {
        A, B, C
    }
}
