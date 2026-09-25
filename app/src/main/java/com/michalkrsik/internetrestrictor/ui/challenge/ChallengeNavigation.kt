package com.michalkrsik.internetrestrictor.ui.challenge

import kotlinx.coroutines.flow.MutableStateFlow

/** Keep the browser covered until the dashboard has submitted a visible frame. */
internal object TaskPickerTransition {
    val pending = MutableStateFlow(false)
}
