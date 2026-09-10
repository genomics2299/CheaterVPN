package libv2ray;

import go.Seq;

/* JADX INFO: loaded from: classes4.dex */
public abstract class Libv2ray {

    private static final class proxyCoreCallbackHandler implements Seq.Proxy, CoreCallbackHandler {
        private final int refnum;

        proxyCoreCallbackHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // libv2ray.CoreCallbackHandler
        public native long onEmitStatus(long j, String str);

        @Override // libv2ray.CoreCallbackHandler
        public native long shutdown();

        @Override // libv2ray.CoreCallbackHandler
        public native long startup();
    }

    private static final class proxyProcessFinder implements Seq.Proxy, ProcessFinder {
        private final int refnum;

        proxyProcessFinder(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // libv2ray.ProcessFinder
        public native long findProcessByConnection(String str, String str2, long j, String str3, long j2);

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }
    }

    static {
        Seq.touch();
        _init();
    }

    private Libv2ray() {
    }

    private static native void _init();

    public static native String checkVersionX();

    public static native String fetchQuicCertSha256(String str);

    public static native String fetchTlsCertSha256(String str);

    public static native void initCoreEnv(String str, String str2);

    public static native long measureOutboundDelay(String str, String str2) throws Exception;

    public static native CoreController newCoreController(CoreCallbackHandler coreCallbackHandler);

    public static native void reconcileBrowserDialer(String str);

    public static void touch() {
    }
}
