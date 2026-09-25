package com.galaxy.airviewdictionary.ui.screen.overlay.voicelist


import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Singleton
import timber.log.Timber


/**
 * Voice 리스트 뷰
 */
@Singleton
class VoiceListView private constructor() : OverlayView() {

    companion object {
        val INSTANCE: VoiceListView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { VoiceListView() }
    }

    private lateinit var viewModel: VoiceListViewModel

    override lateinit var layoutParams: WindowManager.LayoutParams

    override val composable: @Composable () -> Unit = @Composable {
        if (isAttachedToWindow()) {
            VoiceList()
        }
    }

    override suspend fun cast(applicationContext: Context) {
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            windowAnimations = android.R.style.Animation_Toast
            dimAmount = 0.5f
        }

        super.cast(applicationContext)
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        viewModel = overlayService.getVoiceListViewModel()
        super.onServiceConnected(overlayService)
    }

    @Composable
    fun VoiceList() {
        val localView = LocalView.current
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val screenWidth = configuration.screenWidthDp.dp
        val viewWidth = if (isPortrait) screenWidth else screenWidth * (2f / 3f)

        // 다크 모드 여부
        val isDarkMode = isSystemInDarkTheme()

        // 배경 및 텍스트/아이콘 색상
        val backgroundColor = if (isDarkMode) Color(0xFF1F1F1F) else Color(0xFFFEFEFE)
        val headerFooterColor = if (isDarkMode) Color(0xFFFFFFFF) else Color(0xFF000000)
        val contentColor: Color = if (isDarkMode) Color(0xFFFDFDFD) else Color(0xFF232323)
        val borderColor = if (isDarkMode) Color(0xFF6A6A6A) else Color(0xFFD6D6D6)
        val roundedCornerShape = RoundedCornerShape(24.dp)
        val shadowPadding = 1.dp
        val contentHorizontalPadding: Dp = 16.dp

        val voices: List<VoiceListItem> by viewModel.voicesFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = emptyList()
        )

        val lazyListState = rememberLazyListState()

        val showHeaderDivider = remember {
            derivedStateOf {
                lazyListState.firstVisibleItemScrollOffset > 0
            }
        }

        val showFooterDivider = remember {
            derivedStateOf {
                lazyListState.canScrollForward
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = contentHorizontalPadding),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(viewWidth)
                    .padding(shadowPadding)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 0.1.dp,
                            color = borderColor,
                            shape = roundedCornerShape
                        )
                        .shadow(
                            elevation = shadowPadding,
                            spotColor = Color.Black,
                            ambientColor = Color.Black,
                            shape = roundedCornerShape
                        ),
                    shape = roundedCornerShape,
                    color = backgroundColor,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header
                        Row(
                            modifier = Modifier
                                .wrapContentHeight()
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                modifier = Modifier
                                    .background(Color.Transparent)
                                    .padding(start = contentHorizontalPadding, top = 16.dp, bottom = 4.dp),
                                text = stringResource(id = R.string.title_voice_list_view),
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                                color = if (isDarkMode) Color.White else Color.Black
                            )

                            IconButton(
                                modifier = Modifier.padding(end = contentHorizontalPadding),
                                onClick = {
                                    try {
                                        val intent = Intent()
                                        intent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } catch (e: ActivityNotFoundException) {
                                        Timber.w(e, "TTS 데이터 설치 화면 없음")
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add voice",
                                    tint = contentColor
                                )
                            }
                        }

                        AnimatedVisibility(visible = showHeaderDivider.value) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = contentHorizontalPadding),
                                thickness = 0.2.dp,
                                color = Color.Gray
                            )
                        }

                        // 언어 리스트
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            state = lazyListState,
                        ) {
                            itemsIndexed(voices, key = { _, item -> item.id }) { index, item ->
                                val voice = item.voice
                                val language = item.language

                                Button(
                                    onClick = {
                                        // 탭 = 선택 + 들어보기 (별도 재생 버튼 없음)
                                        ViewCompat.performHapticFeedback(localView, HapticFeedbackConstantsCompat.CONFIRM)
                                        viewModel.selectVoice(item)
                                        viewModel.playSampleVoice(voice)
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                                    shape = RectangleShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RectangleShape),
                                    contentPadding = PaddingValues(horizontal = contentHorizontalPadding, vertical = 4.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = item.isActive,
                                            onClick = {
                                                ViewCompat.performHapticFeedback(localView, HapticFeedbackConstantsCompat.CONFIRM)
                                                viewModel.selectVoice(item)
                                                viewModel.playSampleVoice(voice)
                                            },
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = headerFooterColor,
                                                unselectedColor = Color.Gray,
                                            ),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column(
                                            horizontalAlignment = Alignment.Start,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = voice.name,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                                                color = contentColor
                                            )
                                            Text(
                                                text = language.displayName,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }

                                if (index < voices.size - 1 && language != voices[index + 1].language) {
                                    DottedDivider(
                                        horizontalPadding = contentHorizontalPadding,
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = showFooterDivider.value) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = contentHorizontalPadding),
                                thickness = 0.2.dp,
                                color = Color.Gray
                            )
                        }

                        // Footer
                        val coroutineScope = rememberCoroutineScope()
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 18.dp),
                            onClick = {
                                coroutineScope.launch {
                                    delay(200L)
                                    clear()
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                        ) {
                            Text(
                                text = stringResource(id = android.R.string.ok),
                                color = headerFooterColor,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DottedDivider(
    thickness: Dp = 0.7.dp,
    horizontalPadding: Dp
) {
    val isDarkMode = isSystemInDarkTheme()
    val dividerColor = if (isDarkMode) Color(0xFF6A6A6A) else Color(0xFF444444)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
    ) {
        val canvasWidth = size.width
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f), 0f)
        drawLine(
            color = dividerColor,
            start = Offset(x = 0f, y = 0f),
            end = Offset(x = canvasWidth, y = 0f),
            pathEffect = pathEffect,
            strokeWidth = thickness.toPx()
        )
    }
}












