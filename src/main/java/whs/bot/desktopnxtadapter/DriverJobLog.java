package whs.bot.desktopnxtadapter;

import java.io.IOException;

/**
 * Created by misson20000 on 3/5/16.
 */
public class DriverJobLog implements DriverJob {
    private String msg;

    public DriverJobLog(String msg) {
        this.msg = msg;
    }

    @Override
    public void run(Driver driver) throws IOException {
        driver.log(msg);
    }
}
