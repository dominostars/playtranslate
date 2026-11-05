package com.playtranslate.dictionary

import android.database.sqlite.SQLiteDatabase

/** Run [block] while holding an extra SQLite reference on the receiver, so a
 *  concurrent close()+reopen (PackUpgradeOrchestrator's post-install
 *  eviction, a pack uninstall while a capture is still being read) can't
 *  tear the underlying connection down mid-cursor. Returns null if the
 *  connection was already closed before we could acquire, which the caller
 *  treats the same as "no result". Shared by the Japanese and Chinese
 *  managers; [block] must not suspend. */
internal inline fun <T> SQLiteDatabase.withRefcount(block: () -> T): T? {
    try { acquireReference() } catch (_: IllegalStateException) { return null }
    try { return block() } finally { releaseReference() }
}
