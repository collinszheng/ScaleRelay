// Top-level build file. 版本全部走 gradle/libs.versions.toml。
//
// 版本刻意与 WeightDiary（MIT）和 HcSeeder 保持**逐字一致** ——
// 那套组合（AGP 9.0.1 / Kotlin 2.3.20 / Compose BOM 2026.03.01）已在本机真机上验证过，
// 没有任何理由在这里换版本：换版本不会带来收益，只会引入新的不确定性。
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
}
