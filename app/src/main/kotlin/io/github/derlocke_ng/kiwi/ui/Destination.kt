package io.github.derlocke_ng.kiwi.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.derlocke_ng.kiwi.R

/** The destinations of the bottom bar, in the order they are shown. */
enum class Destination(
    val route: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    Home("home", R.string.nav_home, R.drawable.ic_home),
    Services("services", R.string.nav_services, R.drawable.ic_services),
    Machines("machines", R.string.nav_machines, R.drawable.ic_machines),
    More("more", R.string.nav_more, R.drawable.ic_more),
}
