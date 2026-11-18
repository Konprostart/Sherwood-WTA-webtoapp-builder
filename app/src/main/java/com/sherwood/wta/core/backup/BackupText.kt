package com.sherwood.wta.core.backup

import com.sherwood.wta.core.i18n.AppLanguage
import com.sherwood.wta.core.i18n.Strings

/**
 * Backup-package-only strings.
 *
 * They live with the feature rather than in `core/i18n` so the restore/rollback work
 * stays inside its own package. The ten-language `when (Strings.lang)` shape is the
 * same one the shared strings use, including the rule that a block never falls back
 * to `else` for a real language.
 */
internal object BackupText {

    val verifyingArchive: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在校验备份完整性…"
        AppLanguage.ENGLISH -> "Checking backup integrity…"
        AppLanguage.ARABIC -> "جارٍ التحقق من سلامة النسخة الاحتياطية…"
        AppLanguage.PORTUGUESE -> "A verificar a integridade da cópia de segurança…"
        AppLanguage.SPANISH -> "Comprobando la integridad de la copia de seguridad…"
        AppLanguage.FRENCH -> "Vérification de l'intégrité de la sauvegarde…"
        AppLanguage.GERMAN -> "Backup-Integrität wird geprüft…"
        AppLanguage.RUSSIAN -> "Проверка целостности резервной копии…"
        AppLanguage.JAPANESE -> "バックアップの整合性を確認しています…"
        AppLanguage.KOREAN -> "백업 무결성을 확인하는 중…"
    }

    val restoringSettings: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "正在恢复设置…"
        AppLanguage.ENGLISH -> "Restoring settings…"
        AppLanguage.ARABIC -> "جارٍ استعادة الإعدادات…"
        AppLanguage.PORTUGUESE -> "A restaurar as definições…"
        AppLanguage.SPANISH -> "Restaurando los ajustes…"
        AppLanguage.FRENCH -> "Restauration des réglages…"
        AppLanguage.GERMAN -> "Einstellungen werden wiederhergestellt…"
        AppLanguage.RUSSIAN -> "Восстановление настроек…"
        AppLanguage.JAPANESE -> "設定を復元しています…"
        AppLanguage.KOREAN -> "설정을 복원하는 중…"
    }

    /** Shown after a failed restore that was undone in full. */
    val restoreRolledBack: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "恢复失败，你之前的数据已原样还原。"
        AppLanguage.ENGLISH -> "Restore failed. Your previous data was put back unchanged."
        AppLanguage.ARABIC -> "فشلت الاستعادة. تمت إعادة بياناتك السابقة كما كانت دون تغيير."
        AppLanguage.PORTUGUESE -> "A restauração falhou. Os teus dados anteriores foram repostos sem alterações."
        AppLanguage.SPANISH -> "La restauración falló. Tus datos anteriores se han vuelto a dejar tal como estaban."
        AppLanguage.FRENCH -> "La restauration a échoué. Vos données précédentes ont été remises en l'état."
        AppLanguage.GERMAN -> "Wiederherstellung fehlgeschlagen. Ihre bisherigen Daten wurden unverändert zurückgesetzt."
        AppLanguage.RUSSIAN -> "Восстановление не удалось. Прежние данные возвращены без изменений."
        AppLanguage.JAPANESE -> "復元に失敗しました。以前のデータはそのままの状態に戻されています。"
        AppLanguage.KOREAN -> "복원하지 못했습니다. 이전 데이터는 그대로 되돌려 놓았습니다."
    }

    /** Shown when the rollback itself could not finish — never claim success here. */
    val restoreNotRolledBack: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "恢复失败，且未能完全还原之前的状态。请再恢复一次，或检查应用的存储空间。"
        AppLanguage.ENGLISH -> "Restore failed and the previous state could not be fully brought back. Restore again, or check the app's free storage."
        AppLanguage.ARABIC -> "فشلت الاستعادة ولم تتم استعادة الحالة السابقة بالكامل. أعد الاستعادة أو تحقق من مساحة التخزين المتاحة."
        AppLanguage.PORTUGUESE -> "A restauração falhou e o estado anterior não pôde ser totalmente reposto. Restaura outra vez ou verifica o espaço livre."
        AppLanguage.SPANISH -> "La restauración falló y no se pudo recuperar del todo el estado anterior. Vuelve a restaurar o revisa el espacio libre."
        AppLanguage.FRENCH -> "La restauration a échoué et l'état précédent n'a pas pu être entièrement rétabli. Relancez la restauration ou vérifiez l'espace libre."
        AppLanguage.GERMAN -> "Wiederherstellung fehlgeschlagen; der vorherige Zustand konnte nicht vollständig zurückgeholt werden. Erneut wiederherstellen oder freien Speicher prüfen."
        AppLanguage.RUSSIAN -> "Восстановление не удалось, и прежнее состояние не удалось вернуть полностью. Повторите восстановление или проверьте свободное место."
        AppLanguage.JAPANESE -> "復元に失敗し、以前の状態を完全には戻せませんでした。もう一度復元するか、空き容量を確認してください。"
        AppLanguage.KOREAN -> "복원하지 못했고 이전 상태를 완전히 되돌리지 못했습니다. 다시 복원하거나 저장 공간을 확인하세요."
    }

    /** Shown when an interrupted restore from an earlier crash was undone first. */
    val interruptedRestoreRecovered: String get() = when (Strings.lang) {
        AppLanguage.CHINESE -> "上次恢复被中断，其改动已回滚。"
        AppLanguage.ENGLISH -> "A previous restore was interrupted; its changes have been rolled back."
        AppLanguage.ARABIC -> "قُوطعت استعادة سابقة، وتم التراجع عن تغييراتها."
        AppLanguage.PORTUGUESE -> "Uma restauração anterior foi interrompida; as suas alterações foram revertidas."
        AppLanguage.SPANISH -> "Una restauración anterior se interrumpió; sus cambios se han revertido."
        AppLanguage.FRENCH -> "Une restauration précédente a été interrompue ; ses modifications ont été annulées."
        AppLanguage.GERMAN -> "Eine frühere Wiederherstellung wurde unterbrochen; ihre Änderungen wurden zurückgenommen."
        AppLanguage.RUSSIAN -> "Предыдущее восстановление было прервано; его изменения отменены."
        AppLanguage.JAPANESE -> "前回の復元が中断されたため、その変更を元に戻しました。"
        AppLanguage.KOREAN -> "이전 복원이 중단되어 변경 사항을 되돌렸습니다."
    }
}
