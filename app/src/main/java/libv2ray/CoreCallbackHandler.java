package libv2ray;

/* JADX INFO: loaded from: classes4.dex */
public interface CoreCallbackHandler {
    long onEmitStatus(long j, String str);

    long shutdown();

    long startup();
}
