package com.dekadmin.hastield.ha

/**
 * Domaines Home Assistant pris en charge par l'application, et type interne
 * associé (`light`, `thermostat`, `script`).
 */
object EntityKind {

    const val LIGHT = "light"
    const val THERMOSTAT = "thermostat"
    const val SCRIPT = "script"

    /** Type interne d'une entité, ou null si le domaine n'est pas géré. */
    fun of(entityId: String): String? = when {
        entityId.startsWith("light.") -> LIGHT
        entityId.startsWith("climate.") -> THERMOSTAT
        entityId.startsWith("script.") -> SCRIPT
        else -> null
    }

    fun isSupported(entityId: String): Boolean = of(entityId) != null
}
