package info.nightscout.comboctl.parser

/**
 * IDs of known titles.
 *
 * Used during parsing to identify the titles in a language-
 * independent manner by using the parsed screen title as a
 * key in the [knownScreenTitles] table below.
 */
enum class TitleID {
    QUICK_INFO,
    TBR_PERCENTAGE,
    TBR_DURATION,
    HOUR,
    MINUTE,
    YEAR,
    MONTH,
    DAY
}

/**
 * Known screen titles in various languages, associated to corresponding IDs.
 *
 * This table is useful for converting parsed screen titles to
 * IDs, which are language-independent and thus considerably
 * more useful for identifying screens.
 *
 * The titles are written in uppercase, since this shows
 * subtle nuances in characters better.
 */
val knownScreenTitles = mapOf(
    // English
    "QUICK INFO" to TitleID.QUICK_INFO,
    "TBR PERCENTAGE" to TitleID.TBR_PERCENTAGE,
    "TBR DURATION" to TitleID.TBR_DURATION,
    "HOUR" to TitleID.HOUR,
    "MINUTE" to TitleID.MINUTE,
    "YEAR" to TitleID.YEAR,
    "MONTH" to TitleID.MONTH,
    "DAY" to TitleID.DAY,

    // Spanish
    "QUICK INFO" to TitleID.QUICK_INFO,
    "PORCENTAJE DBT" to TitleID.TBR_PERCENTAGE,
    "DURACIÓN DE DBT" to TitleID.TBR_DURATION,
    "HORA" to TitleID.HOUR,
    "MINUTO" to TitleID.MINUTE,
    "AÑO" to TitleID.YEAR,
    "MES" to TitleID.MONTH,
    "DÍA" to TitleID.DAY,

    // French
    "QUICK INFO" to TitleID.QUICK_INFO,
    "VALEUR DU DBT" to TitleID.TBR_PERCENTAGE,
    "DURÉE DU DBT" to TitleID.TBR_DURATION,
    "HEURE" to TitleID.HOUR,
    "MINUTES" to TitleID.MINUTE,
    "ANNÉE" to TitleID.YEAR,
    "MOIS" to TitleID.MONTH,
    "JOUR" to TitleID.DAY,

    // Italian
    "QUICK INFO" to TitleID.QUICK_INFO,
    "PERCENTUALE PBT" to TitleID.TBR_PERCENTAGE,
    "DURATA PBT" to TitleID.TBR_DURATION,
    "IMPOSTARE ORA" to TitleID.HOUR,
    "IMPOSTARE MINUTI" to TitleID.MINUTE,
    "IMPOSTARE ANNO" to TitleID.YEAR,
    "IMPOSTARE MESE" to TitleID.MONTH,
    "IMPOSTARE GIORNO" to TitleID.DAY,

    // Russian
    "QUICK INFO" to TitleID.QUICK_INFO,
    "ПPOЦEHT BБC" to TitleID.TBR_PERCENTAGE,
    "ПPOДOЛЖИT. BБC" to TitleID.TBR_DURATION,
    "ЧАCЫ" to TitleID.HOUR,
    "МИHУTЫ" to TitleID.MINUTE,
    "ГOД" to TitleID.YEAR,
    "МECЯЦ" to TitleID.MONTH,
    "ДEHЬ" to TitleID.DAY,

    // Turkish
    "QUICK INFO" to TitleID.QUICK_INFO,
    "GBH YÜZDESİ" to TitleID.TBR_PERCENTAGE,
    "GBH SÜRESİ" to TitleID.TBR_DURATION,
    "SAAT" to TitleID.HOUR,
    "DAKİKA" to TitleID.MINUTE,
    "YIL" to TitleID.YEAR,
    "AY" to TitleID.MONTH,
    "GÜN" to TitleID.DAY,

    // Polish
    "QUICK INFO" to TitleID.QUICK_INFO,
    "PROCENT TDP" to TitleID.TBR_PERCENTAGE,
    "CZAS TRWANIA TDP" to TitleID.TBR_DURATION,
    "GODZINA" to TitleID.HOUR,
    "MINUTA" to TitleID.MINUTE,
    "ROK" to TitleID.YEAR,
    "MIESIĄC" to TitleID.MONTH,
    "DZIEŃ" to TitleID.DAY,

    // Czech
    "QUICK INFO" to TitleID.QUICK_INFO,
    "PROCENTO DBD" to TitleID.TBR_PERCENTAGE,
    "TRVÁNÍ DBD" to TitleID.TBR_DURATION,
    "HODINA" to TitleID.HOUR,
    "MINUTA" to TitleID.MINUTE,
    "ROK" to TitleID.YEAR,
    "MĚSÍC" to TitleID.MONTH,
    "DEN" to TitleID.DAY,

    // Hungarian
    "QUICK INFO" to TitleID.QUICK_INFO,
    "TBR SZÁZALÉK" to TitleID.TBR_PERCENTAGE,
    "TBR IDŐTARTAM" to TitleID.TBR_DURATION,
    "ÓRA" to TitleID.HOUR,
    "PERC" to TitleID.MINUTE,
    "ÉV" to TitleID.YEAR,
    "HÓNAP" to TitleID.MONTH,
    "NAP" to TitleID.DAY,

    // Slovak
    "QUICK INFO" to TitleID.QUICK_INFO,
    "PERCENTO DBD" to TitleID.TBR_PERCENTAGE,
    "TRVANIE DBD" to TitleID.TBR_DURATION,
    "HODINA" to TitleID.HOUR,
    "MINÚTA" to TitleID.MINUTE,
    "ROK" to TitleID.YEAR,
    "MESIAC" to TitleID.MONTH,
    "DEŇ" to TitleID.DAY,

    // Romanian
    "QUICK INFO" to TitleID.QUICK_INFO,
    "PROCENT RBT" to TitleID.TBR_PERCENTAGE,
    "DURATA RBT" to TitleID.TBR_DURATION,
    "ORĂ" to TitleID.HOUR,
    "MINUT" to TitleID.MINUTE,
    "AN" to TitleID.YEAR,
    "LUNĂ" to TitleID.MONTH,
    "ZI" to TitleID.DAY,

    // Croatian
    "QUICK INFO" to TitleID.QUICK_INFO,
    "POSTOTAK PBD-A" to TitleID.TBR_PERCENTAGE,
    "TRAJANJE PBD-A" to TitleID.TBR_DURATION,
    "SAT" to TitleID.HOUR,
    "MINUTE" to TitleID.MINUTE,
    "GODINA" to TitleID.YEAR,
    "MJESEC" to TitleID.MONTH,
    "DAN" to TitleID.DAY,

    // Dutch
    "QUICK INFO" to TitleID.QUICK_INFO,
    "TBD-PERCENTAGE" to TitleID.TBR_PERCENTAGE,
    "TBD-DUUR" to TitleID.TBR_DURATION,
    "UREN" to TitleID.HOUR,
    "MINUTEN" to TitleID.MINUTE,
    "JAAR" to TitleID.YEAR,
    "MAAND" to TitleID.MONTH,
    "DAG" to TitleID.DAY,

    // Greek
    "QUICK INFO" to TitleID.QUICK_INFO,
    "ПOΣOΣTO П.B.P." to TitleID.TBR_PERCENTAGE,
    "ΔIАPKEIА П.B.P." to TitleID.TBR_DURATION,
    "ΩPА" to TitleID.HOUR,
    "ΛEПTO" to TitleID.MINUTE,
    "ETOΣ" to TitleID.YEAR,
    "МHNАΣ" to TitleID.MONTH,
    "HМEPА" to TitleID.DAY,

    // Finnish
    "QUICK INFO" to TitleID.QUICK_INFO,
    "TBA - PROSENTTI" to TitleID.TBR_PERCENTAGE,
    "TBA - KESTO" to TitleID.TBR_DURATION,
    "TUNTI" to TitleID.HOUR,
    "MINUUTTI" to TitleID.MINUTE,
    "VUOSI" to TitleID.YEAR,
    "KUUKAUSI" to TitleID.MONTH,
    "PÄIVÄ" to TitleID.DAY,

    // Norwegian
    "QUICK INFO" to TitleID.QUICK_INFO,
    "MBD-PROSENT" to TitleID.TBR_PERCENTAGE,
    "MBD-VARIGHET" to TitleID.TBR_DURATION,
    "TIME" to TitleID.HOUR,
    "MINUTT" to TitleID.MINUTE,
    "ÅR" to TitleID.YEAR,
    "MÅNED" to TitleID.MONTH,
    "DAG" to TitleID.DAY,

    // Portuguese
    "QUICK INFO" to TitleID.QUICK_INFO,
    "DBT PERCENTAGEM" to TitleID.TBR_PERCENTAGE,
    "DBT DURAÇÃO" to TitleID.TBR_DURATION,
    "HORA" to TitleID.HOUR,
    "MINUTO" to TitleID.MINUTE,
    "ANO" to TitleID.YEAR,
    "MÊS" to TitleID.MONTH,
    "DIA" to TitleID.DAY,

    // Swedish
    "QUICK INFO" to TitleID.QUICK_INFO,
    "TBD PROCENT" to TitleID.TBR_PERCENTAGE,
    "TBD DURATION" to TitleID.TBR_DURATION,
    "TIMME" to TitleID.HOUR,
    "MINUT" to TitleID.MINUTE,
    "ÅR" to TitleID.YEAR,
    "MÅNAD" to TitleID.MONTH,
    "DAG" to TitleID.DAY,

    // Danish
    "QUICK INFO" to TitleID.QUICK_INFO,
    "MBR-PROCENT" to TitleID.TBR_PERCENTAGE,
    "MBR-VARIGHED" to TitleID.TBR_DURATION,
    "TIME" to TitleID.HOUR,
    "MINUT" to TitleID.MINUTE,
    "ÅR" to TitleID.YEAR,
    "MÅNED" to TitleID.MONTH,
    "DAG" to TitleID.DAY,

    // German
    "QUICK INFO" to TitleID.QUICK_INFO,
    "TBR WERT" to TitleID.TBR_PERCENTAGE,
    "TBR DAUER" to TitleID.TBR_DURATION,
    "STUNDE" to TitleID.HOUR,
    "MINUTE" to TitleID.MINUTE,
    "JAHR" to TitleID.YEAR,
    "MONAT" to TitleID.MONTH,
    "TAG" to TitleID.DAY,

    // Some pumps came preconfigured with a different quick info name
    "ACCU CHECK SPIRIT" to TitleID.QUICK_INFO
)
