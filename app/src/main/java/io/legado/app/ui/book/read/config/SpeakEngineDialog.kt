package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemHttpTtsBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 朗读引擎选择。
 *
 * 只读取**系统提供**的 TTS 引擎(`TextToSpeech.getEngines()`):
 * 不再有自建 HTTP 引擎的添加/编辑/删除/导入/导出/清理缓存等管理功能。
 * 选中即**全局生效**(写入 [AppConfig.ttsEngine])并立即重启朗读会话。
 */
class SpeakEngineDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel: SpeakEngineViewModel by viewModels()
    private val adapter by lazy { Adapter(requireContext()) }
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private var ttsEngine: String? = ReadAloud.ttsEngine

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.speak_engine)
            // 只做选择, 不需要工具栏菜单(原增删改/导入导出已移除)
            toolBar.menu.clear()
            recyclerView.setEdgeEffectColor(primaryColor)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
            tvCancel.visible()
            tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        }
        val rows = arrayListOf(EngineRow(null, getString(R.string.system_tts)))
        viewModel.sysEngines.forEach { rows.add(EngineRow(it.name, it.label)) }
        adapter.setItems(rows)
        upChecked()
    }

    /** 当前选中的系统引擎 name; 未选择(或旧值指向已删除的 HTTP 引擎)时回落到「系统默认」。 */
    private fun selectedName(): String? {
        val value = GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.value
        return value?.takeIf { it.isNotBlank() }
    }

    private fun upChecked() {
        val selected = selectedName()
        adapter.getItems().forEach { row -> row.checked = row.name == selected }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private fun applyEngine(name: String?, label: String) {
        val newValue = if (name.isNullOrBlank()) null else GSON.toJson(SelectItem(label, name))
        ttsEngine = newValue
        AppConfig.ttsEngine = newValue
        ReadAloud.upReadAloudClass()
        callBack?.upSpeakEngineSummary()
        dismissAllowingStateLoss()
    }

    data class EngineRow(val name: String?, val label: String, var checked: Boolean = false)

    inner class Adapter(context: Context) :
        RecyclerAdapter<EngineRow, ItemHttpTtsBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemHttpTtsBinding {
            return ItemHttpTtsBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemHttpTtsBinding,
            item: EngineRow,
            payloads: MutableList<Any>
        ) {
            binding.run {
                ivEdit.gone()
                ivMenuDelete.gone()
                labelSys.visible()
                cbName.text = item.label
                cbName.isChecked = item.checked
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemHttpTtsBinding) {
            binding.cbName.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition)
                    ?: return@setOnClickListener
                applyEngine(item.name, item.label)
            }
        }

    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }

}
