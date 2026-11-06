package com.dekadmin.hastield.ha

/**
 * Logique d'ordre du dashboard, isolée pour être testable sans Android.
 *
 * L'invariant est simple : l'ordre contient **exactement** les entités
 * sélectionnées, une fois chacune. Toute autre forme (doublons, entités
 * désélectionnées, entités inconnues) rendait les déplacements ±1 imprévisibles.
 */
object Ordering {

    /** Réordonne [order] pour qu'il corresponde à [selected], en gardant l'ordre connu. */
    fun normalize(order: List<String>, selected: Set<String>): List<String> {
        val kept = order.filter { it in selected }.distinct()
        val missing = selected.filterNot { it in kept }.sorted()
        return kept + missing
    }

    /** Déplace [entityId] de [delta] positions ; sans effet hors des bornes. */
    fun move(order: List<String>, entityId: String, delta: Int): List<String> {
        val current = order.toMutableList()
        val index = current.indexOf(entityId)
        if (index < 0) return current
        val target = index + delta
        if (target < 0 || target >= current.size) return current
        current.removeAt(index)
        current.add(target, entityId)
        return current
    }

    /** Position (1-based) d'une entité dans l'ordre, 0 si absente. */
    fun positionOf(order: List<String>, entityId: String): Int =
        order.indexOf(entityId).let { if (it < 0) 0 else it + 1 }
}
