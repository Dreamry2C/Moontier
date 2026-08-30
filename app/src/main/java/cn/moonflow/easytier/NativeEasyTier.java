package cn.moonflow.easytier;


public final class NativeEasyTier {
    static {
        System.loadLibrary("easytier_ffi");
        System.loadLibrary("moontier_native_bridge");
    }

    private NativeEasyTier() {}

    public static native void setLogLevel(String level);
    public static native int parseConfig(String config);
    public static native int runNetworkInstance(String config);
    public static native int retainNetworkInstance(String[] instanceNames);
    public static native String collectNetworkInfos(int maxLength);
    public static native int setTunFd(String instanceName, int fd);
    public static native String getLastError();

    public static int stopAllInstances() {
        return retainNetworkInstance(null);
    }

}
