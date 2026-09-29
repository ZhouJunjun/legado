package io.legado.app.utils

import java.util.regex.Pattern

/**
 * 从规则 URL 中提取「参数段」的匹配模式。
 *
 * 匹配形如 `url,{key:value}` 里紧接 `{` 之前的那个逗号（含其前后空白），
 * 用于把 `url,{...}` 形式的规则拆成纯 URL 与参数两部分。
 *
 * 原先位于 AnalyzeUrl 的伴生对象；离线化改造移除 AnalyzeRule 体系后独立出来，
 * 供 utils / data / ui 多个包共用（原先的 `AnalyzeUrl.paramPattern` 仅能由 analyzeRule 包访问）。
 */
val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")
