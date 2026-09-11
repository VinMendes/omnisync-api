package com.puccampinas.omnisync.core.audit;

/** WEB does not guess whether an older client triggered the request automatically. */
public enum AuditSource {
    WEB, MANUAL, AUTOMATIC, WEBHOOK, OAUTH_CALLBACK, SYSTEM, SIGNUP, PASSWORD_RESET
}
