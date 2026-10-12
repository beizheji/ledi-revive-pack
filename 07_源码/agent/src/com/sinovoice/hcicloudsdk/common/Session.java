package com.sinovoice.hcicloudsdk.common;

/**
 * 灵云 SDK Session（会话句柄）。
 * 与官方 com.sinovoice.hcicloudsdk.common.Session 结构一致：
 * 内部仅一个 int 字段（sessionId），native 层持有真实会话资源。
 */
public class Session {
    public static final int INVALID_SESSION_ID = -1;
    private int a = INVALID_SESSION_ID;

    public Session() {
    }

    public Session copy() {
        Session s = new Session();
        s.a = this.a;
        return s;
    }

    public boolean equal(Session other) {
        return other != null && other.a == this.a;
    }

    public int getSessionId() {
        return a;
    }

    public void setSessionId(int id) {
        this.a = id;
    }
}
