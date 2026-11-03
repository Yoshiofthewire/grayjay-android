package android.util;

public class Log {
    public static int d(String tag, String msg) {
        System.out.println("DEBUG: " + tag + ": " + msg);
        return 0;
    }

    public static int i(String tag, String msg) {
        System.out.println("INFO: " + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("WARN: " + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg) {
        System.out.println("ERROR: " + tag + ": " + msg);
        return 0;
    }

    public static int v(String tag, String msg) { return d(tag, msg); }
    public static int w(String tag, String msg, Throwable tr) { return w(tag, msg + " " + tr); }
    public static int e(String tag, String msg, Throwable tr) { return e(tag, msg + " " + tr); }
    public static int println(int priority, String tag, String msg) { return d(tag, msg); }
    public static boolean isLoggable(String tag, int level) { return false; }
    public static String getStackTraceString(Throwable tr) { return String.valueOf(tr); }
}
