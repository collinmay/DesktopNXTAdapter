package whs.bot.desktopnxtadapter;

import javax.bluetooth.BluetoothStateException;

/**
 * Created by misson20000 on 3/18/17.
 */
public class Main {
    public static void main(String[] args) {
        RobotService service = new RobotService();
        try {
            service.start();
        } catch (BluetoothStateException e) {
            e.printStackTrace();
        }
    }
}
