package io.nekohasekai.sfa.compose.screen.smart

import io.nekohasekai.sfa.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SmartInsightsLabelsTest {
    @Test
    fun smartGroupLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "starting" to R.string.smart_starting,
            "ok" to R.string.smart_ok,
            "degraded" to R.string.smart_degraded,
            "all_dead" to R.string.smart_all_dead,
            "offline" to R.string.smart_offline,
            "paused" to R.string.smart_paused,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartGroupLabel(core)) }
        assertEquals(R.string.smart_unknown, smartGroupLabel("unrecognized"))
    }

    @Test
    fun smartGroupExplanationCoversEverySpecifiedValue() {
        val cases = listOf(
            "starting" to R.string.smart_starting_explanation,
            "ok" to R.string.smart_ok_explanation,
            "degraded" to R.string.smart_degraded_explanation,
            "all_dead" to R.string.smart_all_dead_explanation,
            "offline" to R.string.smart_offline_explanation,
            "paused" to R.string.smart_paused_explanation,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartGroupExplanation(core)) }
        assertEquals(R.string.smart_unknown, smartGroupExplanation("unrecognized"))
    }

    @Test
    fun smartMemberLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "unknown" to R.string.smart_not_checked_yet,
            "healthy" to R.string.smart_healthy,
            "suspect" to R.string.smart_suspect,
            "probation" to R.string.smart_probation,
            "quarantined" to R.string.smart_quarantined,
            "emergency" to R.string.smart_emergency,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartMemberLabel(core)) }
        assertEquals(R.string.smart_unknown, smartMemberLabel("unrecognized"))
    }

    @Test
    fun smartEvidenceLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "ok" to R.string.smart_evidence_ok,
            "failed" to R.string.smart_failed,
            "unknown" to R.string.smart_evidence_unknown,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartEvidenceLabel(core)) }
        assertEquals(R.string.smart_unknown, smartEvidenceLabel("unrecognized"))
    }

    @Test
    fun smartDirectLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "ok" to R.string.smart_direct_ok,
            "failed" to R.string.smart_direct_failed,
            "unknown" to R.string.smart_direct_unknown,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartDirectLabel(core)) }
        assertEquals(R.string.smart_unknown, smartDirectLabel("unrecognized"))
    }

    @Test
    fun smartNetworkTypeLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "wifi" to R.string.smart_wifi,
            "cellular" to R.string.smart_cellular,
            "ethernet" to R.string.smart_ethernet,
            "other" to R.string.smart_other,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartNetworkTypeLabel(core)) }
        assertEquals(R.string.smart_unknown, smartNetworkTypeLabel("unrecognized"))
    }

    @Test
    fun unnamedNetworkFallbacksMatchEveryType() {
        assertEquals(R.string.smart_unnamed_wifi, smartNetworkFallbackLabel("wifi"))
        assertEquals(R.string.smart_unnamed_mobile, smartNetworkFallbackLabel("cellular"))
        assertEquals(R.string.smart_ethernet, smartNetworkFallbackLabel("ethernet"))
        assertEquals(R.string.smart_other, smartNetworkFallbackLabel("other"))
        assertEquals(R.string.smart_unknown, smartNetworkFallbackLabel("unrecognized"))
    }

    @Test
    fun networkCountPluralsMatchEveryType() {
        assertEquals(R.plurals.smart_access_point_count, smartNetworkCountPlural("wifi"))
        assertEquals(R.plurals.smart_cell_count, smartNetworkCountPlural("cellular"))
        assertEquals(R.plurals.smart_connection_count, smartNetworkCountPlural("ethernet"))
        assertEquals(R.plurals.smart_connection_count, smartNetworkCountPlural("other"))
        assertEquals(R.plurals.smart_connection_count, smartNetworkCountPlural("unrecognized"))
    }

    @Test
    fun budgetFormatsOmitTheReservedClauseAtZero() {
        assertEquals(R.string.smart_budget_values, smartBudgetValuesResource(0.0))
        assertEquals(R.string.smart_budget_values, smartBudgetValuesResource(-0.0))
        assertEquals(R.string.smart_budget_values, smartBudgetValuesResource(Double.NaN))
        assertEquals(R.string.smart_budget_values, smartBudgetValuesResource(Double.POSITIVE_INFINITY))
        assertEquals(R.string.smart_budget_values_reserved, smartBudgetValuesResource(0.1))
        val english = strings("values")
        val russian = strings("values-ru-rRU")
        assertEquals("1.0 MB of 100 MB on Wi‑Fi", String.format(english.getValue("smart_budget_values"), "1.0 MB", "100 MB", "Wi‑Fi"))
        assertEquals("1.0 MB of 100 MB on Wi‑Fi · reserved 2.0 MB", String.format(english.getValue("smart_budget_values_reserved"), "1.0 MB", "100 MB", "Wi‑Fi", "2.0 MB"))
        assertEquals("1,0 МБ из 100 МБ в сети Wi‑Fi", String.format(russian.getValue("smart_budget_values"), "1,0 МБ", "100 МБ", "Wi‑Fi"))
        assertEquals("1,0 МБ из 100 МБ в сети Wi‑Fi · резерв 2,0 МБ", String.format(russian.getValue("smart_budget_values_reserved"), "1,0 МБ", "100 МБ", "Wi‑Fi", "2,0 МБ"))
    }

    @Test
    fun smartQuicLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "primary" to R.string.smart_primary,
            "member" to R.string.smart_member,
            "reject" to R.string.smart_reject,
            "off" to R.string.smart_off,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartQuicLabel(core)) }
        assertEquals(R.string.smart_unknown, smartQuicLabel("unrecognized"))
    }

    @Test
    fun smartThroughputSourceLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "active" to R.string.smart_speedtest,
            "under_load" to R.string.smart_source_under_load,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartThroughputSourceLabel(core)) }
        assertEquals(R.string.smart_unknown, smartThroughputSourceLabel("unrecognized"))
        assertEquals(R.string.smart_unknown, smartThroughputSourceLabel("passive"))
    }

    @Test
    fun smartEventLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "network_change" to R.string.smart_network_change,
            "emergency" to R.string.smart_emergency_event,
            "speedtest" to R.string.smart_speedtest,
            "recover" to R.string.smart_recover,
            "quarantine" to R.string.smart_quarantine_event,
            "failover" to R.string.smart_failover,
            "all_dead" to R.string.smart_all_dead_event,
            "import" to R.string.smart_import_event,
            "reset" to R.string.smart_reset_done,
            "switch" to R.string.smart_switch,
            "tier_restore" to R.string.smart_tier_restore,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartEventLabel(core)) }
        assertEquals(R.string.smart_unknown, smartEventLabel("unrecognized"))
    }

    @Test
    fun smartReasonLabelCoversEverySpecifiedValue() {
        val cases = listOf(
            "default network changed" to R.string.smart_reason_network,
            "no eligible primary" to R.string.smart_reason_no_primary,
            "active throughput measurement" to R.string.smart_reason_scheduled,
            "fresh degradation pair" to R.string.smart_reason_slowdown,
            "three half-open successes" to R.string.smart_reason_three_checks,
            "first reachable member" to R.string.smart_reason_first_response,
            "first success this network" to R.string.smart_reason_first_success,
            "two probe operators failed" to R.string.smart_reason_two_checks,
            "primary corroborated unreachable" to R.string.smart_reason_unreachable,
            "best reachable member" to R.string.smart_reason_best,
            "contained validation; no health evidence" to R.string.smart_reason_internal_error,
            "all members failed while direct internet works" to R.string.smart_reason_no_response,
            "imported historical priors" to R.string.smart_reason_import,
            "learned history reset; live health and budget retained" to R.string.smart_reason_reset,
            "confirmed stability or throughput improvement" to R.string.smart_reason_improved,
            "demand challenger meets target" to R.string.smart_reason_target,
            "preferred tier healthy for 60 seconds" to R.string.smart_reason_preferred,
        )
        cases.forEach { (core, resource) -> assertEquals(core, resource, smartReasonLabel(core)) }
        assertEquals(R.string.smart_unknown, smartReasonLabel("unrecognized"))
        assertNull(smartReasonLabel(""))
    }

    @Test
    fun englishAndRussianCopyMatchesTheSpecification() {
        val expected = listOf(
            Triple("smart_servers", "Servers", "Серверы"),
            Triple("smart_details", "Details", "Подробности"),
            Triple("smart_networks", "Networks", "Сети"),
            Triple("smart_current_server", "Current server", "Текущий сервер"),
            Triple("smart_none", "None", "Нет"),
            Triple("smart_standby_server", "Standby: %1\$s", "Резерв: %1\$s"),
            Triple("smart_direct_summary", "Direct internet: %1\$s", "Прямой интернет: %1\$s"),
            Triple("smart_current", "Current", "Текущий"),
            Triple("smart_standby", "Standby", "Резерв"),
            Triple("smart_show_details", "Show details", "Показать подробности"),
            Triple("smart_hide_details", "Hide details", "Скрыть подробности"),
            Triple("smart_expanded", "Expanded", "Развёрнуто"),
            Triple("smart_collapsed", "Collapsed", "Свёрнуто"),
            Triple("smart_tcp", "TCP", "TCP"),
            Triple("smart_udp", "UDP", "UDP"),
            Triple("smart_quic", "QUIC (UDP/443)", "QUIC (UDP/443)"),
            Triple("smart_latency", "Check time", "Время проверки"),
            Triple("smart_latency_p90", "Check time p90", "Время проверки p90"),
            Triple("smart_stability", "Stability", "Стабильность"),
            Triple("smart_download", "Download", "Загрузка"),
            Triple("smart_upload", "Upload", "Отдача"),
            Triple("smart_round_trip", "Round-trip time", "Время отклика"),
            Triple("smart_jitter", "Jitter", "Джиттер"),
            Triple("smart_upload_unverified", "Not verified", "Не подтверждено"),
            Triple("smart_observed_during_use", "Observed during use", "Наблюдалось при работе"),
            Triple("smart_slowdown_check", "Slowdown check", "Проверка замедления"),
            Triple("smart_under_load", "Under load", "Под нагрузкой"),
            Triple("smart_slowdown_revoked", "Below %1\$s Mbit/s under load · %2\$s", "Ниже %1\$s Мбит/с под нагрузкой · %2\$s"),
            Triple("smart_slowdown_proven", "Reached %1\$s Mbit/s under load · %2\$s", "Достиг %1\$s Мбит/с под нагрузкой · %2\$s"),
            Triple("smart_under_load_value", "%1\$.1f Mbit/s · %2\$s", "%1\$.1f Мбит/с · %2\$s"),
            Triple("smart_download_p10_value", "%1\$.1f Mbit/s · p10 %2\$.1f", "%1\$.1f Мбит/с · p10 %2\$.1f"),
            Triple("smart_profile_fastest_upload", "Fastest upload", "Максимальная отдача"),
            Triple("smart_profile_fastest_upload_description", "Highest verified upload speed", "Самая высокая подтверждённая скорость отдачи"),
            Triple("smart_editor_upload", "Upload speed", "Скорость отдачи"),
            Triple("smart_editor_upload_hint", "Verified upload speed", "Подтверждённая скорость отдачи"),
            Triple("smart_editor_rtt", "Round-trip time", "Время отклика"),
            Triple("smart_editor_rtt_hint", "Measured with UDP probes", "Измеряется UDP-пробами"),
            Triple("smart_editor_jitter", "Jitter", "Джиттер"),
            Triple("smart_editor_jitter_hint", "How much round-trip time varies", "Насколько меняется время отклика"),
            Triple("smart_editor_latency", "Check time", "Время проверки"),
            Triple("smart_editor_spread", "Check time spread", "Разброс времени проверки"),
            Triple("smart_editor_streams", "Speed-test connections", "Соединения теста скорости"),
            Triple("smart_editor_streams_value", "Parallel connections per speed test: %1\$d", "Параллельных соединений при тесте скорости: %1\$d"),
            Triple("smart_speed_class", "Speed class", "Класс скорости"),
            Triple("smart_tier", "Tier", "Уровень"),
            Triple("smart_weight", "Weight", "Вес"),
            Triple("smart_probation_label", "Probation", "Испытательный срок"),
            Triple("smart_set_aside_until", "Set aside until", "Исключён до"),
            Triple("smart_last_success", "Last success", "Последний успех"),
            Triple("smart_last_failure", "Last failure", "Последняя ошибка"),
            Triple("smart_rtt_value", "%d ms", "%d мс"),
            Triple("smart_speed_value", "%.1f Mbit/s", "%.1f Мбит/с"),
            Triple("smart_mb_value", "%,.1f MB", "%,.1f МБ"),
            Triple("smart_mb_whole_value", "%,.0f MB", "%,.0f МБ"),
            Triple("smart_percent_value", "%d%%", "%d%%"),
            Triple("smart_stability_value", "%1\$d%% · class %2\$s", "%1\$d%% · класс %2\$s"),
            Triple("smart_speed_class_value", "%1\$d · %2\$s · %3\$s", "%1\$d · %2\$s · %3\$s"),
            Triple("smart_weight_value", "%.2f", "%.2f"),
            Triple("smart_probation_value", "%d/3", "%d/3"),
            Triple("smart_charts_caption", "Current network · last 24 hours · gaps mean no data", "Текущая сеть · последние 24 часа · пропуски означают отсутствие данных"),
            Triple("smart_group", "Group", "Группа"),
            Triple("smart_udp_quic", "UDP / QUIC", "UDP / QUIC"),
            Triple("smart_udp_route", "UDP: %1\$s", "UDP: %1\$s"),
            Triple("smart_quic_route", "QUIC: %1\$s", "QUIC: %1\$s"),
            Triple("smart_budget", "Speed-test data today", "Трафик тестов скорости сегодня"),
            Triple("smart_budget_values", "%1\$s of %2\$s on %3\$s", "%1\$s из %2\$s в сети %3\$s"),
            Triple("smart_budget_values_reserved", "%1\$s of %2\$s on %3\$s · reserved %4\$s", "%1\$s из %2\$s в сети %3\$s · резерв %4\$s"),
            Triple("smart_budget_note", "Speed tests only, not VPN traffic", "Только тесты скорости, не трафик VPN"),
            Triple("smart_active_tests_off", "Active tests disabled", "Активные тесты выключены"),
            Triple("smart_direct_title", "Direct internet", "Прямой интернет"),
            Triple("smart_checked", "checked %1\$s", "проверено %1\$s"),
            Triple("smart_history_empty", "No per-network history yet", "Истории по сетям пока нет"),
            Triple("smart_last_seen", "last used %1\$s", "последнее использование %1\$s"),
            Triple("smart_show_all", "Show all (%d)", "Показать все (%d)"),
            Triple("smart_starting", "Checking servers", "Проверка серверов"),
            Triple("smart_ok", "Working", "Работает"),
            Triple("smart_degraded", "Limited availability", "Ограниченная доступность"),
            Triple("smart_all_dead", "Servers unavailable", "Серверы недоступны"),
            Triple("smart_offline", "Connection unavailable", "Нет подключения"),
            Triple("smart_paused", "Paused", "Приостановлено"),
            Triple("smart_starting_explanation", "Testing servers on this network", "Проверяем серверы в этой сети"),
            Triple("smart_ok_explanation", "The current server is working", "Текущий сервер работает"),
            Triple("smart_degraded_explanation", "The preferred server isn't fully healthy right now", "Основной сервер сейчас работает нестабильно"),
            Triple("smart_all_dead_explanation", "The internet works, but no server responds", "Интернет работает, но серверы не отвечают"),
            Triple("smart_offline_explanation", "Can't reach the internet right now", "Сейчас нет доступа к интернету"),
            Triple("smart_paused_explanation", "Server selection is paused", "Выбор сервера приостановлен"),
            Triple("smart_not_checked_yet", "Not checked yet", "Ещё не проверен"),
            Triple("smart_healthy", "Healthy", "Работает"),
            Triple("smart_suspect", "Unstable", "Нестабилен"),
            Triple("smart_probation", "Recovering", "Восстанавливается"),
            Triple("smart_quarantined", "Set aside", "Временно исключён"),
            Triple("smart_emergency", "Emergency use", "Аварийное использование"),
            Triple("smart_evidence_ok", "OK", "Работает"),
            Triple("smart_failed", "Failed", "Не работает"),
            Triple("smart_evidence_unknown", "Not checked", "Не проверено"),
            Triple("smart_direct_ok", "Available", "Доступен"),
            Triple("smart_direct_failed", "Unavailable", "Недоступен"),
            Triple("smart_direct_unknown", "Not checked", "Не проверен"),
            Triple("smart_unknown", "Unknown", "Неизвестно"),
            Triple("smart_wifi", "Wi‑Fi", "Wi‑Fi"),
            Triple("smart_cellular", "Mobile", "Мобильная сеть"),
            Triple("smart_ethernet", "Ethernet", "Ethernet"),
            Triple("smart_other", "Other", "Другая"),
            Triple("smart_primary", "Through the current server", "Через текущий сервер"),
            Triple("smart_member", "Through %1\$s", "Через %1\$s"),
            Triple("smart_reject", "Blocked; apps may retry over TCP", "Заблокирован; приложения могут повторить подключение по TCP"),
            Triple("smart_off", "Normal UDP routing", "Обычная маршрутизация UDP"),
            Triple("smart_source_under_load", "under load", "под нагрузкой"),
            Triple("smart_network_change", "Network changed", "Сеть изменилась"),
            Triple("smart_emergency_event", "Emergency mode", "Аварийный режим"),
            Triple("smart_speedtest", "Speed test", "Тест скорости"),
            Triple("smart_recover", "Server recovered", "Сервер восстановлен"),
            Triple("smart_quarantine_event", "Server set aside", "Сервер временно исключён"),
            Triple("smart_failover", "Switched to a backup", "Переключение на резервный"),
            Triple("smart_all_dead_event", "No servers available", "Серверы недоступны"),
            Triple("smart_import_event", "History imported", "История импортирована"),
            Triple("smart_switch", "Switched server", "Сервер сменён"),
            Triple("smart_tier_restore", "Back to preferred servers", "Возврат к основным серверам"),
            Triple("smart_reason_network", "Connected to a different network", "Подключение к другой сети"),
            Triple("smart_reason_no_primary", "No server passed checks", "Ни один сервер не прошёл проверку"),
            Triple("smart_reason_scheduled", "Scheduled speed check", "Плановая проверка скорости"),
            Triple("smart_reason_slowdown", "Checking a slowdown", "Проверка замедления"),
            Triple("smart_reason_three_checks", "Passed three checks in a row", "Три успешные проверки подряд"),
            Triple("smart_reason_first_response", "First server that responded", "Первый ответивший сервер"),
            Triple("smart_reason_first_success", "First success on this network", "Первый успех в этой сети"),
            Triple("smart_reason_two_checks", "Failed two independent checks", "Не прошёл две независимые проверки"),
            Triple("smart_reason_unreachable", "Current server confirmed unreachable", "Текущий сервер подтверждённо недоступен"),
            Triple("smart_reason_best", "Best server that responds", "Лучший из отвечающих серверов"),
            Triple("smart_reason_internal_error", "Check interrupted by an internal error; server health unknown", "Проверка прервана внутренней ошибкой; состояние сервера неизвестно"),
            Triple("smart_reason_no_response", "Internet works, but no server responds", "Интернет работает, но серверы не отвечают"),
            Triple("smart_reason_import", "Imported from a file", "Импорт из файла"),
            Triple("smart_reason_reset", "Learned history cleared", "Накопленная история очищена"),
            Triple("smart_reason_improved", "More stable or faster", "Стабильнее или быстрее"),
            Triple("smart_reason_target", "Meets the speed target", "Достигает целевой скорости"),
            Triple("smart_reason_preferred", "Preferred servers healthy for a minute", "Основные серверы стабильны минуту"),
            Triple("smart_reset_done", "History reset", "История сброшена"),
            Triple("smart_events", "Recent events", "Последние события"),
            Triple("smart_events_empty", "No events yet", "Событий пока нет"),
            Triple("smart_unnamed_wifi", "Unnamed Wi‑Fi network", "Сеть Wi‑Fi без имени"),
            Triple("smart_unnamed_mobile", "Unnamed mobile network", "Мобильная сеть без имени"),
            Triple("smart_event_repeated", "%1\$s ×%2\$d", "%1\$s ×%2\$d"),
            Triple("smart_event_since", "since %1\$s", "с %1\$s"),
        )
        val english = strings("values")
        val russian = strings("values-ru-rRU")
        expected.forEach { (name, en, ru) ->
            assertEquals(name, en, english[name])
            assertEquals(name, ru, russian[name])
        }
    }

    @Test
    fun networkServerCountsHaveAllRequiredPluralForms() {
        assertEquals(mapOf("one" to "%d server", "other" to "%d servers"), plurals("values"))
        assertEquals(mapOf("one" to "%d сервер", "few" to "%d сервера", "many" to "%d серверов", "other" to "%d сервера"), plurals("values-ru-rRU"))
    }

    @Test
    fun networkEntryCountsHaveEveryEnglishAndRussianPluralForm() {
        assertEquals(mapOf("one" to "%d access point", "other" to "%d access points"), plurals("values", "smart_access_point_count"))
        assertEquals(mapOf("one" to "%d cell", "other" to "%d cells"), plurals("values", "smart_cell_count"))
        assertEquals(mapOf("one" to "%d connection", "other" to "%d connections"), plurals("values", "smart_connection_count"))
        assertEquals(mapOf("one" to "%d точка доступа", "few" to "%d точки доступа", "many" to "%d точек доступа", "other" to "%d точки доступа"), plurals("values-ru-rRU", "smart_access_point_count"))
        assertEquals(mapOf("one" to "%d сота", "few" to "%d соты", "many" to "%d сот", "other" to "%d соты"), plurals("values-ru-rRU", "smart_cell_count"))
        assertEquals(mapOf("one" to "%d подключение", "few" to "%d подключения", "many" to "%d подключений", "other" to "%d подключения"), plurals("values-ru-rRU", "smart_connection_count"))
    }

    private fun document(folder: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/strings_smart.xml"))

    private fun strings(folder: String): Map<String, String> {
        val nodes = document(folder).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index) as Element
            node.getAttribute("name") to node.textContent.replace("\\'", "'")
        }
    }

    private fun plurals(folder: String, name: String = "smart_server_count"): Map<String, String> {
        val nodes = document(folder).getElementsByTagName("plurals")
        val count = (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("name") == name }
        val items = count.getElementsByTagName("item")
        return (0 until items.length).associate { index ->
            val item = items.item(index) as Element
            item.getAttribute("quantity") to item.textContent
        }
    }
}
