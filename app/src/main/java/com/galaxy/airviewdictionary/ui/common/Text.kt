package com.galaxy.airviewdictionary.ui.common

import androidx.annotation.DimenRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import timber.log.Timber

/**
 * 텍스트가 주어진 제약 조건에 맞게 자동으로 크기를 조절하는 컴포저블.
 * 텍스트가 주어진 가로 또는 세로 제약을 초과할 경우, 지정된 최소 폰트 크기까지 폰트 크기를 줄여 텍스트가 모두 표시될 수 있도록 한다.
 *
 * @param text 표시할 텍스트 문자열.
 * @param maxFontSize 텍스트의 초기 최대 폰트 크기.
 * @param minFontSize 텍스트가 맞지 않을 경우 줄일 수 있는 최소 폰트 크기.
 * @param enableAutoResize 텍스트의 자동 크기 조절 기능을 켜거나 끄는 플래그.
 * @param modifier Text를 포함한 Box에 적용할 Modifier.
 * @param onReadyToDisplay 레이아웃이 끝나 표시할 준비가 되었을 때, 실제로 그려진 높이(px)와 함께 호출된다.
 *        호출부가 그 높이에 창을 맞출 수 있도록 넘긴다 — 미리 잰 값과 어긋나면 빈 공간이 남기 때문이다.
 */
@Composable
fun AutoResizeText(
    text: AnnotatedString,
    maxFontSize: TextUnit = 60.sp,
    minFontSize: TextUnit = 8.sp,
    enableAutoResize: Boolean = true,
    inlineContent: Map<String, InlineTextContent> = mapOf(), // 텍스트에 삽입할 인라인 콘텐츠(예: 스피커 아이콘)
    modifier: Modifier = Modifier,
    onReadyToDisplay: (renderedHeightPx: Int) -> Unit // 텍스트 준비 완료 시 실제 높이와 함께 호출
) {
    // 현재 폰트 크기를 나타내며, 처음에는 최대 폰트 크기로 설정됩니다.
    var fontSize by remember { mutableStateOf(maxFontSize) }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center // 텍스트를 박스 내 가운데 정렬
    ) {
        // 제약 조건의 최대 너비와 높이를 가져와서 텍스트가 범위 내에 맞는지 확인
        val constraintsMaxWidth = with(LocalDensity.current) { maxWidth.toPx() }
        val constraintsMaxHeight = with(LocalDensity.current) { maxHeight.toPx() }

        Text(
            text = text,
            fontSize = fontSize,
            inlineContent = inlineContent,
            maxLines = Int.MAX_VALUE, // 여러 줄의 텍스트를 박스 내에서 허용
            textAlign = TextAlign.Start, // 텍스트를 시작 지점(기본적으로 왼쪽)부터 정렬
            onTextLayout = { textLayoutResult ->
                // 자동 크기 조절이 활성화된 경우에만 크기 조절 로직 수행
                if (enableAutoResize) {
                    // 너비 또는 높이가 초과되었을 경우 폰트 크기를 10% 씩 줄임
                    if ((textLayoutResult.didOverflowHeight || textLayoutResult.didOverflowWidth) && fontSize > minFontSize) {
                        fontSize = (fontSize.value * .9).coerceAtLeast(minFontSize.value.toDouble()).sp
                        Timber.tag("AutoResizeText").d("AutoResizeText [${fontSize}] ")
                    } else {
                        // 텍스트가 맞거나 최소 폰트 크기에 도달하면 표시 준비 완료로 간주하고 콜백 호출
                        onReadyToDisplay(textLayoutResult.size.height)
                    }
                }
            }
        )
    }
}

@Composable
@ReadOnlyComposable
fun fontDimensionResource(@DimenRes id: Int) = dimensionResource(id = id).value.sp