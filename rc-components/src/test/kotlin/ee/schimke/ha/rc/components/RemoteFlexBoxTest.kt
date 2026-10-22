@file:OptIn(ExperimentalFlexBoxApi::class)

package ee.schimke.ha.rc.components

import androidx.compose.foundation.layout.ExperimentalFlexBoxApi
import androidx.compose.foundation.layout.FlexBasis
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteFlexBoxTest {
  @Test
  fun decodesEveryFlexBasisKind() {
    assertEquals(Basis.Auto, FlexBasis.Auto.decode())
    assertEquals(Basis.Fixed(64.dp), FlexBasis.Dp(64.dp).decode())
    assertEquals(Basis.Fixed(0.dp), FlexBasis.Dp(0.dp).decode())
    assertEquals(Basis.Fixed(12.5.dp), FlexBasis.Dp(12.5.dp).decode())
    assertEquals(Basis.Fraction(0.25f), FlexBasis.Percent(0.25f).decode())
    assertEquals(Basis.Fraction(1f / 3f), FlexBasis.Percent(1f / 3f).decode())
  }
}
