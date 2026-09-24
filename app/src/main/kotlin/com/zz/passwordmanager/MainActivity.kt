package com.zz.passwordmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zz.passwordmanager.ui.AppNav
import dagger.hilt.android.AndroidEntryPoint

/** M2 宿主：Hilt + Compose 导航（列表/详情/编辑/解锁）。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppNav(viewModel = viewModel())
            }
        }
    }
}
