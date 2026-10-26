package io.github.jichuo1.meguviz.ui.android

import io.github.jichuo1.meguviz.android.runtime.resources.ResourceScope
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState
import io.github.jichuo1.meguviz.ui.ResourceAuthority

/** Tokens and validity remain owned by the actual upstream resource domain. */
fun androidResourceAuthority(scope: ResourceScope): ResourceAuthority = androidResourceAuthority(scope.state())

fun androidResourceAuthority(state: ResourceState): ResourceAuthority = ResourceAuthority(
    capture = { state.token(ResourceState.ALL) },
    isCurrent = { token -> token is ResourceState.Token && state.isCurrent(token) },
)
