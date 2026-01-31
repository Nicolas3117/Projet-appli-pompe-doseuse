package com.esp32pumpwifi.app

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox

class EspModuleAdapter(
    private val context: Context,
    private var modules: MutableList<EspModule>,
    private val onRename: (EspModule) -> Unit,
    private val onDelete: (EspModule) -> Unit,
    private val onSelect: (EspModule) -> Unit     // 🔥 Callback module sélectionné
) : BaseAdapter() {

    override fun getCount(): Int = modules.size
    override fun getItem(position: Int): EspModule = modules[position]
    override fun getItemId(position: Int): Long = modules[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {

        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.list_item_esp32, parent, false)

        val module = modules[position]

        val txtName   = view.findViewById<TextView>(R.id.txt_name)
        val txtIp     = view.findViewById<TextView>(R.id.txt_ip)
        val btnEdit   = view.findViewById<ImageView>(R.id.icon_edit)
        val btnDelete = view.findViewById<ImageView>(R.id.icon_delete)
        val checkBox  = view.findViewById<MaterialCheckBox>(R.id.check_select)

        // -------------------------------------------------------------------
        // 🎨 AFFICHAGE
        // -------------------------------------------------------------------
        val displayName = module.displayName.ifBlank { module.internalName }
        txtName.text = displayName
        txtIp.text   = module.ip

        // Couleur verte = module actif
        txtName.setTextColor(
            if (module.isActive) Color.parseColor("#2E7D32") else Color.BLACK
        )

        // IMPORTANT : éviter que la vue recyclée applique l'ancien listener
        checkBox.setOnCheckedChangeListener(null)
        checkBox.isChecked = module.isActive

        // -------------------------------------------------------------------
        // ☑ SÉLECTION EXCLUSIVE
        // -------------------------------------------------------------------
        checkBox.setOnClickListener {

            // Si déjà actif : ne peut pas être décoché
            if (module.isActive) {
                checkBox.isChecked = true
                return@setOnClickListener
            }

            // Sélection exclusive
            modules.forEach {
                it.isActive = it.id == module.id
                Esp32Manager.update(context, it)
            }

            notifyDataSetChanged()
            onSelect(module)
        }

        // Clic sur toute la ligne = sélection
        view.setOnClickListener { checkBox.performClick() }

        // -------------------------------------------------------------------
        // ✏ RENOMMER / 🗑 SUPPRIMER
        // -------------------------------------------------------------------
        btnEdit.setOnClickListener { onRename(module) }
        btnDelete.setOnClickListener { onDelete(module) }

        return view
    }

    fun refresh(newList: MutableList<EspModule>) {
        modules = newList
        notifyDataSetChanged()
    }
}
