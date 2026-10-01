package com.rubicon.analytics.testapp

import android.app.Activity
import androidx.activity.ComponentActivity
import com.rubicon.analytics.ScreenName

/** The launcher; its automatic screen is named from the class: `Main`. */
class MainActivity : Activity()

/** Named by the annotation, which R8 must keep (step 8.2). */
@ScreenName("checkout")
class CheckoutActivity : Activity()

/** `singleTop`: a second link arrives through `onNewIntent` (D13). */
class LinkActivity : ComponentActivity()
