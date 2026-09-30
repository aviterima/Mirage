package com.mirage.spike.store

/** Shared search and ordering for browsing and adding saved content. */
fun savedItemSearch(items: List<SavedScenario>, query: String, kind: String? = null, favoritesOnly: Boolean = false): List<SavedScenario> =
    items.filter { item ->
        (kind==null || item.kind==kind) && (!favoritesOnly || item.favorite) &&
            (item.name+" "+item.destName+" "+item.destAddress+" "+item.aliases.joinToString(" ")+" "+item.stops.joinToString(" "){it.name+" "+it.address}).contains(query.trim(),true)
    }.sortedWith(compareByDescending<SavedScenario>{it.favorite}.thenByDescending{it.lastUsedAt}.thenByDescending{it.createdAt})
