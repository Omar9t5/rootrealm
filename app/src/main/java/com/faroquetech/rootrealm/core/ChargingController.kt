package com.faroquetech.rootrealm.core

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Kernel-aware, root-based charging control helper.
 *
 * This does NOT assume any fixed vendor (e.g. Xiaomi) node path. Instead it scans
 * /sys/class/power_supply/ directories at runtime for a curated set of node NAMES that are
 * recognized, cross-kernel charging-current control attributes (used by common
 * Qualcomm / MTK / AOSP power_supply drivers). Only paths built from this curated
 * name list are ever touched - this class never writes to an arbitrary writable
 * sysfs file it happens to find, only to a node that is actually appropriate for
 * charging control.
 *
 * All exists/readable/writable checks are done via the root shell (`test`), because
 * those checks from a non-root Java process are meaningless for files only root can
 * access. Important caveat: `test -w` only reflects DAC (Unix permission bit)
 * access - it does NOT know about SELinux. A node can report writable == true here
 * and still be blocked by the kernel's SELinux policy at the moment of the actual
 * write. That's why every write is followed by a verification read: "writable" from
 * test -w is a necessary check, never proof the kernel will actually accept a value.
 */
object ChargingController {

    // Priority order: most standard / most likely to be a *real* charge-current
    // control node first. Only these node names are ever considered when scanning -
    // never an arbitrary writable file under power_supply.
    private val CANDIDATE_NODE_NAMES = listOf(
        "constant_charge_current_max",
        "constant_charge_current",
        "input_current_limit",
        "input_current_max",
        "charge_current_limit",
        "current_max"
    )

    data class ChargeNode(
        val path: String,
        val readable: Boolean,
        val writable: Boolean,
        val value: String?
    )

    enum class WriteOutcome { CONFIRMED, ADJUSTED, REJECTED, ERROR }

    data class WriteResult(
        val outcome: WriteOutcome,
        val before: String?,
        val after: String?,
        val error: String?
    )

    /** True only if `su` actually grants a root shell (uid=0), not just that the binary exists. */
    fun isRootAvailable(): Boolean = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
        val exit = proc.waitFor()
        exit == 0 && out.contains("uid=0")
    } catch (_: Exception) {
        false
    }

    /** Returns "Enforcing" / "Permissive" / "Disabled" / "Unknown". Requires root to query reliably. */
    fun getSELinuxStatus(): String = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "getenforce 2>/dev/null"))
        proc.waitFor()
        when (BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim()?.lowercase()) {
            "enforcing" -> "Enforcing"
            "permissive" -> "Permissive"
            "disabled" -> "Disabled"
            else -> "Unknown"
        }
    } catch (_: Exception) {
        "Unknown"
    }

    /**
     * Scans every directory under /sys/class/power_supply/ for the curated node
     * names above, in a single root shell invocation (cheap: one su call instead of
     * one per candidate), and reports exists/readable/writable/current-value for
     * each match found. Returned in priority order: node-name priority first, then
     * discovery order of power_supply directories within that name.
     */
    private fun scanCandidates(): List<ChargeNode> {
        val names = CANDIDATE_NODE_NAMES.joinToString(" ")
        val script = "D=\$(ls -1 /sys/class/power_supply/ 2>/dev/null); " +
            "for n in $names; do " +
            "for d in \$D; do " +
            "f=\"/sys/class/power_supply/\$d/\$n\"; " +
            "if [ -e \"\$f\" ]; then " +
            "r=0; w=0; " +
            "[ -r \"\$f\" ] && r=1; " +
            "[ -w \"\$f\" ] && w=1; " +
            "v=\"\"; " +
            "[ \"\$r\" = \"1\" ] && v=\$(cat \"\$f\" 2>/dev/null); " +
            "echo \"\$f|\$r|\$w|\$v\"; " +
            "fi; " +
            "done; " +
            "done"
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            proc.waitFor()
            BufferedReader(InputStreamReader(proc.inputStream)).readLines()
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 4)
                    if (parts.size < 3) return@mapNotNull null
                    val path = parts[0].trim()
                    if (path.isEmpty()) return@mapNotNull null
                    val readable = parts[1].trim() == "1"
                    val writable = parts[2].trim() == "1"
                    val value = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                    ChargeNode(path, readable, writable, value)
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Finds the best suitable charging-current control node on this kernel:
     *  - prefers the highest-priority node that is both readable AND writable
     *  - falls back to the highest-priority existing node (so the UI can still show
     *    its value and the "not writable" message) if none are writable
     *  - returns null only if no known charging-control node exists at all
     */
    fun findControlNode(): ChargeNode? {
        val candidates = scanCandidates()
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.writable && it.readable } ?: candidates.first()
    }

    /** Rereads a single node's current value via root. */
    fun readValue(path: String): String? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '${path.replace("'", "")}' 2>/dev/null"))
        proc.waitFor()
        BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * Returns the kernel-declared/requestable current ceiling for the same power-supply
     * directory as [nodePath]. Only explicit maximum/limit nodes are accepted as a
     * ceiling; instantaneous current nodes are never treated as a maximum. Values are
     * expected in microamps, as used by Linux power_supply current attributes.
     */
    fun getKernelRequestableCurrentUa(nodePath: String): Long? {
        val clean = nodePath.trim().removeSuffix("/")
        if (!clean.startsWith("/sys/class/power_supply/") || clean.contains("'")) return null

        val directory = clean.substringBeforeLast('/', "")
        val base = clean.substringAfterLast('/')
        val maxNames = listOf(
            "constant_charge_current_max",
            "input_current_max",
            "charge_current_limit",
            "current_max"
        )

        val candidates = if (base in maxNames) {
            listOf(clean)
        } else {
            maxNames.map { "$directory/$it" }
        }

        for (candidate in candidates) {
            val value = readValue(candidate)
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            if (value != null) return value
        }
        return null
    }

    /**
     * Detects whether the device exposes a real wireless-charging receiver/supply.
     * Android has no public PackageManager feature flag specifically for wireless
     * charging, so detection is done from the power_supply topology. We require
     * either a Wireless power-supply type, a wireless/RX-specific sysfs attribute,
     * or a wireless-named supply with charging-state attributes. This prevents the
     * Wireless Charging UI from appearing on ordinary wired-only devices.
     */
    fun isWirelessChargingSupported(): Boolean {
        val dollar = '$'
        val script = """
            for d in /sys/class/power_supply/*; do
                [ -d "${dollar}d" ] || continue
                type=""; [ -r "${dollar}d/type" ] && type=${dollar}(cat "${dollar}d/type" 2>/dev/null)
                utype=""; [ -r "${dollar}d/uevent" ] && utype=${dollar}(grep -m1 '^POWER_SUPPLY_TYPE=' "${dollar}d/uevent" 2>/dev/null | cut -d= -f2-)
                case "${dollar}type" in Wireless|wireless|WIRELESS) echo 1; exit ;; esac
                case "${dollar}utype" in Wireless|wireless|WIRELESS) echo 1; exit ;; esac
                case "${dollar}d" in *wireless*|*wlc*|*wls*|*wpc*|*rx*)
                    for n in wireless_input_voltage_now wireless_input_voltage input_voltage_now input_voltage vbus_voltage_now vbus_voltage rx_voltage_now rx_voltage rect_voltage_now rect_voltage vrect_now vrect wireless_input_current_now wireless_input_current input_current_now input_current ibus_current_now ibus_current rx_current_now rx_current wireless_input_power_now wireless_input_power input_power_now input_power rx_power_now rx_power rect_power_now rect_power wireless_power_now wireless_power; do
                        [ -e "${dollar}d/${dollar}n" ] && echo 1 && exit
                    done
                    for n in online status present; do
                        [ -e "${dollar}d/${dollar}n" ] && echo 1 && exit
                    done
                    ;;
                esac
            done
            echo 0
        """.trimIndent()
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            proc.waitFor()
            BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim() == "1"
        } catch (_: Exception) { false }
    }

    /** Finds a writable/readable charging-current control node belonging to a wireless
     * charging power-supply. Only known current-control attribute names are considered. */
    fun findWirelessControlNode(): ChargeNode? {
        val names = CANDIDATE_NODE_NAMES.joinToString(" ")
        val dollar = '$'
        val script = "D=${dollar}(ls -1 /sys/class/power_supply/ 2>/dev/null); " +
            "for d in ${dollar}D; do " +
            "case ${dollar}d in *wireless*|*wlc*|*wls*|*rx*) ;; *) continue ;; esac; " +
            "for n in $names; do " +
            "f=\"/sys/class/power_supply/${dollar}d/${dollar}n\"; " +
            "if [ -e \"${dollar}f\" ]; then " +
            "r=0; w=0; [ -r \"${dollar}f\" ] && r=1; [ -w \"${dollar}f\" ] && w=1; " +
            "v=\"\"; [ \"${dollar}r\" = \"1\" ] && v=${dollar}(cat \"${dollar}f\" 2>/dev/null); " +
            "echo \"${dollar}f|${dollar}r|${dollar}w|${dollar}v\"; fi; done; done"
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            proc.waitFor()
            val candidates = BufferedReader(InputStreamReader(proc.inputStream)).readLines().mapNotNull { line ->
                val parts = line.split("|", limit = 4)
                if (parts.size < 3) return@mapNotNull null
                val path = parts[0].trim()
                if (path.isEmpty()) return@mapNotNull null
                ChargeNode(path, parts[1].trim() == "1", parts[2].trim() == "1", parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() })
            }
            candidates.firstOrNull { it.readable && it.writable } ?: candidates.firstOrNull()
        } catch (_: Exception) { null }
    }

    /**
     * Reads several attributes from the same discovered wireless power_supply directory.
     * This prevents voltage/current/power values from being accidentally mixed together
     * when a device exposes more than one wireless-related supply. Only attribute names
     * supplied by the app are read; no arbitrary sysfs path is accepted here.
     */
    fun readWirelessSupplyValues(attributes: List<String>): Map<String, String> {
        val safe = attributes.filter { it.matches(Regex("[A-Za-z0-9_]+")) }.distinct()
        if (safe.isEmpty()) return emptyMap()
        val dollar = '$'
        val names = safe.joinToString(" ")
        // Prefer a wireless/RX supply that exposes explicit input-side measurements.
        // Only fall back to a status-only supply when no such measurement supply exists.
        val script = """
            best=""
            best_type=""
            for d in /sys/class/power_supply/*; do
                dtype=""; [ -r "${dollar}d/type" ] && dtype=${dollar}(cat "${dollar}d/type" 2>/dev/null)
                case ${dollar}d in
                    *wireless*|*wlc*|*wls*|*wpc*|*rx*) ;;
                    *) case ${dollar}dtype in Wireless|wireless|WIRELESS) ;; *) continue ;; esac ;;
                esac
                has_input=0
                for n in input_voltage_now input_voltage bus_voltage_now bus_voltage vbus_voltage_now vbus_voltage rx_voltage_now rx_voltage rect_voltage_now rect_voltage vrect_now vrect wireless_voltage_now wireless_voltage wls_voltage_now wls_voltage wlc_voltage_now wlc_voltage wpc_voltage_now wpc_voltage wireless_input_voltage_now wireless_input_voltage voltage_now input_current_now input_current ibus_current_now ibus_current bus_current_now bus_current vbus_current_now vbus_current rx_current_now rx_current rect_current_now rect_current wireless_current_now wireless_current wls_current_now wls_current wlc_current_now wlc_current wpc_current_now wpc_current wireless_input_current_now wireless_input_current current_now input_power_now input_power ibus_power_now ibus_power bus_power_now bus_power rx_power_now rx_power rect_power_now rect_power wireless_power_now wireless_power wls_power_now wls_power wlc_power_now wlc_power wpc_power_now wpc_power wireless_input_power_now wireless_input_power power_now; do
                    f="${dollar}d/${dollar}n"
                    if [ -r "${dollar}f" ]; then has_input=1; break; fi
                done
                if [ "${dollar}has_input" = "1" ]; then
                    printf '__supply__|%s|%s\n' "${dollar}d" "${dollar}dtype";
                    for n in $names; do
                        f="${dollar}d/${dollar}n"
                        if [ -r "${dollar}f" ]; then
                            v=${dollar}(cat "${dollar}f" 2>/dev/null)
                            printf '%s|%s\n' "${dollar}n" "${dollar}v"
                        fi
                    done
                    exit
                fi
                if [ -z "${dollar}best" ]; then best="${dollar}d"; best_type="${dollar}dtype"; fi
            done
            if [ -n "${dollar}best" ]; then
                d="${dollar}best"
                printf '__supply__|%s|%s\n' "${dollar}d" "${dollar}best_type";
                for n in $names; do
                    f="${dollar}d/${dollar}n"
                    if [ -r "${dollar}f" ]; then
                        v=${dollar}(cat "${dollar}f" 2>/dev/null)
                        printf '%s|%s\n' "${dollar}n" "${dollar}v"
                    fi
                done
            fi
        """.trimIndent()
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            proc.waitFor()
            BufferedReader(InputStreamReader(proc.inputStream)).readLines()
                .mapNotNull { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1].trim() else null
                }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    /** Reads a value from the first wireless power-supply exposing the attribute. */
    fun readWirelessSupplyValue(attribute: String): String? {
        val dollar = '$'
        val script = "for d in /sys/class/power_supply/*; do case ${dollar}d in *wireless*|*wlc*|*wls*|*rx*) f=\"${dollar}d/$attribute\"; [ -r \"${dollar}f\" ] && cat \"${dollar}f\" && exit ;; esac; done"
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            proc.waitFor()
            BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /**
     * Writes a new value to a verified charging-control node and reads it back
     * afterward. Only CONFIRMED counts as success:
     *  - CONFIRMED: kernel now reports exactly the value we asked for
     *  - ADJUSTED: the value did change, but not to what we asked (kernel clamped /
     *    rounded it to a supported step) - reported, never silently treated as success
     *  - REJECTED: the value never changed at all (common SELinux-denial outcome:
     *    the write() syscall may even return success while the driver silently drops it)
     *  - ERROR: the shell/write itself failed (exit code, exception)
     */
    fun writeAndVerify(path: String, rawValue: String): WriteResult {
        val before = readValue(path)
        return try {
            val safe = rawValue.replace("'", "'\\''")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "printf '%s' '$safe' > '$path'"))
            val exit = proc.waitFor()
            val err = BufferedReader(InputStreamReader(proc.errorStream)).readText().trim()
            val after = readValue(path)
            val outcome = when {
                after == null -> if (exit == 0) WriteOutcome.REJECTED else WriteOutcome.ERROR
                after == rawValue.trim() -> WriteOutcome.CONFIRMED
                after != before -> WriteOutcome.ADJUSTED
                else -> WriteOutcome.REJECTED
            }
            WriteResult(outcome, before, after, err.takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            WriteResult(WriteOutcome.ERROR, before, null, e.message)
        }
    }
}
