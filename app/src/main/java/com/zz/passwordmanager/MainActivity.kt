package com.zz.passwordmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.AndroidEntryPoint

/** 空壳主界面：T01 验收「可构建运行」的最小可见页面。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text(text = stringResource(R.string.app_name))
            }
        }
    }
}
