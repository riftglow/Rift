package light.rift.group

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import light.rift.R
import light.rift.database.GroupManager
import light.rift.database.ProxyGroup
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.runOnMainDispatcher
import light.rift.ui.ThemedActivity
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCoroutine {
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(R.string.yes) { _, _ -> it.resume(true) }
                    .setNegativeButton(R.string.no) { _, _ -> it.resume(false) }
                    .setOnCancelListener { _ -> it.resume(false) }
                    .show()
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean,
        skipped: List<String>
    ) {
        if (changed == 0 && duplicate.isEmpty() && skipped.isEmpty()) {
            if (byUser) context.snackbar(
                    context.getString(
                            R.string.group_no_difference, group.displayName()
                    )
            ).show()
        } else {
            context.snackbar(context.getString(R.string.group_updated, group.name, changed)).show()

            var status = ""
            if (added.isNotEmpty()) {
                status += context.getString(
                        R.string.group_added, added.joinToString("\n", postfix = "\n\n")
                )
            }
            if (updated.isNotEmpty()) {
                status += context.getString(R.string.group_changed,
                        updated.map { it }.joinToString("\n", postfix = "\n\n") {
                            if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                        })
            }
            if (deleted.isNotEmpty()) {
                status += context.getString(
                        R.string.group_deleted, deleted.joinToString("\n", postfix = "\n\n")
                )
            }
            if (duplicate.isNotEmpty()) {
                status += context.getString(
                        R.string.group_duplicate, duplicate.joinToString("\n", postfix = "\n\n")
                )
            }
            // Batch 2 item 6: skipped entries are shown in the same
            // dialog, so they cannot pass unnoticed even when nothing
            // else changed.
            if (skipped.isNotEmpty()) {
                status += context.getString(
                        R.string.group_skipped, skipped.joinToString("\n", postfix = "\n\n")
                )
            }

            onMainDispatcher {
                delay(1000L)

                MaterialAlertDialogBuilder(context).setTitle(
                        context.getString(
                                R.string.group_diff, group.displayName()
                        )
                ).setMessage(status.trim()).setPositiveButton(android.R.string.ok, null).show()
            }

        }

    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            // 失败只弹 3.5 秒底部横条看不到（用户实测连点 5 次全没看到）：
            // 改弹窗，停留到用户点掉。403 是订阅链接过期（服务商限制，
            // 不是 Rift 的 bug），用大白话；其他失败仍显示原始错误文案。
            val linkExpired = message.contains("403")
                    || message.contains("Forbidden", ignoreCase = true)
            MaterialAlertDialogBuilder(context).setTitle(group.displayName())
                .setMessage(
                    if (linkExpired) context.getString(R.string.subscription_link_expired)
                    else message
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override suspend fun alert(message: String) {
        return suspendCoroutine {
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> it.resume(Unit) }
                    .setOnCancelListener { _ -> it.resume(Unit) }
                    .show()
            }
        }
    }

}