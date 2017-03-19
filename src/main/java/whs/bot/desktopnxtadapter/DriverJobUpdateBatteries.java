package whs.bot.desktopnxtadapter;

import java.io.IOException;

/**
 * Created by misson20000 on 3/5/16.
 */
public class DriverJobUpdateBatteries implements DriverJob {
    @Override
    public void run(Driver driver) throws IOException {
        driver.writeBatteryInfo();
        driver.log("wrote battery info");
    }
}
