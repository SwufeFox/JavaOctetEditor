/*
 * Copyright 2022 Enaium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.enaium.joe.gui.panel.method;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.util.asm.OpcodeUtil;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.dialog.search.SearchFieldDialog;
import cn.enaium.joe.dialog.search.SearchLdcDialog;
import cn.enaium.joe.dialog.search.SearchMethodDialog;
import cn.enaium.joe.gui.component.InstructionComboBox;
import cn.enaium.joe.gui.panel.confirm.InstructionEditPanel;
import cn.enaium.joe.util.Util;
import cn.enaium.joe.util.*;
import cn.enaium.joe.util.wrapper.InstructionWrapper;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.HashMap;

/**
 * @author Enaium
 * @since 0.8.0
 */
public class MethodInstructionPanel extends JPanel {
    public MethodInstructionPanel(MethodNode methodNode) {
        super(new BorderLayout());
        DefaultListModel<InstructionWrapper> instructionDefaultListModel = new DefaultListModel<>();
        JList<InstructionWrapper> instructionJList = new JList<>(instructionDefaultListModel);
        instructionJList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        instructionJList.setPrototypeCellValue(new InstructionWrapper(null));
        instructionJList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> new JPanel(new BorderLayout()) {{
            if (isSelected) {
                setBackground(list.getSelectionBackground());
            } else {
                setBackground(list.getBackground());
            }
            add(new JLabel(String.format("%04d ", index)), BorderLayout.WEST);
            add(new JLabel(value.toString()), BorderLayout.CENTER);
        }});
        for (AbstractInsnNode instruction : methodNode.instructions) {
            instructionDefaultListModel.addElement(new InstructionWrapper(instruction));
        }

        JPopupMenu jPopupMenu = new JPopupMenu();
        ApplicationConfig.KeymapConfig keymapConfig = JavaOctetEditor.getInstance().CONFIG.getByClass(ApplicationConfig.class).keymap.getValue();

        addItem(instructionJList, jPopupMenu, LangUtil.i18n("popup.instructions.edit"), () -> {
            InstructionWrapper selectedValue = instructionJList.getSelectedValue();
            if (selectedValue != null && !(selectedValue.getWrapper() instanceof LabelNode)) {
                MessageUtil.confirm(new InstructionEditPanel(selectedValue.getWrapper()), LangUtil.i18n("popup.instructions.edit"));
            }
        }, keymapConfig.edit.getValue());


        addItem(instructionJList, jPopupMenu, LangUtil.i18n("popup.instructions.clone"), () -> {
            InstructionWrapper selectedValue = instructionJList.getSelectedValue();
            if (instructionJList.getSelectedIndex() != -1 && selectedValue != null) {
                AbstractInsnNode clone;
                if (selectedValue.getWrapper() instanceof LabelNode) {
                    clone = new LabelNode();
                } else {
                    HashMap<LabelNode, LabelNode> labels = new HashMap<>();
                    for (AbstractInsnNode node : methodNode.instructions) {
                        if (node instanceof LabelNode label) labels.put(label, label);
                    }
                    clone = selectedValue.getWrapper().clone(labels);
                }

                instructionDefaultListModel.add(instructionJList.getSelectedIndex() + 1, new InstructionWrapper(clone));
                methodNode.instructions.insert(selectedValue.getWrapper(), clone);
            }
        }, keymapConfig.clone.getValue());

        addItem(instructionJList, jPopupMenu, LangUtil.i18n("popup.instructions.remove"), () -> {
            InstructionWrapper selectedValue = instructionJList.getSelectedValue();
            if (instructionJList.getSelectedIndex() != -1 && selectedValue != null) {
                MessageUtil.confirm(LangUtil.i18n("dialog.wantRemove"), LangUtil.i18n("button.remove"), () -> {
                    instructionDefaultListModel.removeElement(selectedValue);
                    methodNode.instructions.remove(selectedValue.getWrapper());
                });
            }
        }, keymapConfig.remove.getValue());

        addItem(instructionJList, jPopupMenu, LangUtil.i18n("popup.instructions.copyText"), () -> {
            InstructionWrapper selectedValue = instructionJList.getSelectedValue();
            if (instructionJList.getSelectedIndex() != -1 && selectedValue != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(HtmlUtil.remove(selectedValue.toString())), null);
            }
        }, keymapConfig.copy.getValue());
        addItem(instructionJList, jPopupMenu, LangUtil.i18n("button.search"), () -> {
            InstructionWrapper selectedValue = instructionJList.getSelectedValue();
            if (instructionJList.getSelectedIndex() != -1 && selectedValue != null) {
                AbstractInsnNode node = selectedValue.getWrapper();
                if (node instanceof LdcInsnNode){
                    new SearchLdcDialog(((LdcInsnNode)node)).setVisible(true);
                } else if (node instanceof FieldInsnNode){
                    new SearchFieldDialog((FieldInsnNode) node).setVisible(true);
                } else if (node instanceof MethodInsnNode){
                    new SearchMethodDialog((MethodInsnNode) node).setVisible(true);
                }
            }
        });

        addItem(instructionJList, jPopupMenu, LangUtil.i18n("popup.instructions.insertBefore"), () -> insert(methodNode, instructionJList, true), keymapConfig.insertBefore.getValue());
        addItem(instructionJList, jPopupMenu, LangUtil.i18n("popup.instructions.insertAfter"), () -> insert(methodNode, instructionJList, false), keymapConfig.insertAfter.getValue());
        addItem(instructionJList, jPopupMenu, LangUtil.i18n("popup.instructions.moveUp"), () -> moveInstruction(instructionJList, methodNode, true), keymapConfig.moveUp.getValue());
        addItem(instructionJList, jPopupMenu, LangUtil.i18n("popup.instructions.moveDown"), () -> moveInstruction(instructionJList, methodNode, false), keymapConfig.moveDown.getValue());

        JMenuUtil.addPopupMenu(instructionJList, () -> jPopupMenu, () -> instructionJList.getSelectedValue() != null);
        add(new JScrollPane(instructionJList), BorderLayout.CENTER);
    }

    private void addItem(JList<InstructionWrapper> instructionJList, JPopupMenu jPopupMenu, String text, Runnable runnable) {
        jPopupMenu.add(new JMenuItem(text) {{
            addActionListener(Util.ofAction(runnable));
        }});
    }

    private void addItem(JList<InstructionWrapper> instructionJList, JPopupMenu jPopupMenu, String text, Runnable runnable, KeyStroke key) {
        jPopupMenu.add(new JMenuItem(text) {{
            KeyStrokeUtil.register(instructionJList, key, runnable);
            setAccelerator(key);
            addActionListener(Util.ofAction(runnable));
        }});
    }

    private static void moveInstruction(JList<InstructionWrapper> instructionJList, MethodNode methodNode, boolean up) {
        DefaultListModel<InstructionWrapper> model = (DefaultListModel<InstructionWrapper>) instructionJList.getModel();
        int index = instructionJList.getSelectedIndex();
        InstructionWrapper selected = instructionJList.getSelectedValue();
        int target = index + (up ? -1 : 1);
        if (selected == null || index < 0 || target < 0 || target >= model.size()) return;
        AbstractInsnNode neighbor = model.get(target).getWrapper();
        methodNode.instructions.remove(selected.getWrapper());
        if (up) methodNode.instructions.insertBefore(neighbor, selected.getWrapper());
        else methodNode.instructions.insert(neighbor, selected.getWrapper());
        model.remove(index);
        model.add(target, selected);
        instructionJList.setSelectedIndex(target);
        instructionJList.ensureIndexIsVisible(target);
    }

    private static void insert(MethodNode methodNode, JList<InstructionWrapper> instructionJList, boolean before) {
        if (instructionJList.getSelectedIndex() == -1 || instructionJList.getSelectedValue() == null) {
            return;
        }
        DefaultListModel<InstructionWrapper> instructionDefaultListModel = ((DefaultListModel<InstructionWrapper>) instructionJList.getModel());
        InstructionWrapper anchor = instructionJList.getSelectedValue();
        InstructionComboBox instructionComboBox = new InstructionComboBox();
        MessageUtil.confirm(instructionComboBox, "select insert instruction", () -> {
            AbstractInsnNode abstractInsnNode;
            int selectedIndex = instructionComboBox.getSelectedIndex();
            switch (selectedIndex) {
                case AbstractInsnNode.INSN:
                    abstractInsnNode = new InsnNode(Opcodes.NOP);
                    break;
                case AbstractInsnNode.INT_INSN:
                    abstractInsnNode = new IntInsnNode(Opcodes.BIPUSH, 0);
                    break;
                case AbstractInsnNode.VAR_INSN:
                    abstractInsnNode = new VarInsnNode(Opcodes.ILOAD, 0);
                    break;
                case AbstractInsnNode.TYPE_INSN:
                    abstractInsnNode = new TypeInsnNode(Opcodes.NEW, "java/lang/Object");
                    break;
                case AbstractInsnNode.FIELD_INSN:
                    abstractInsnNode = new FieldInsnNode(Opcodes.GETSTATIC, "", "", "");
                    break;
                case AbstractInsnNode.METHOD_INSN:
                    abstractInsnNode = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "", "", "", false);
                    break;
                case AbstractInsnNode.INVOKE_DYNAMIC_INSN:
                    abstractInsnNode = new InvokeDynamicInsnNode("", "", new Handle(Opcodes.H_GETFIELD, "", "", "", false));
                    break;
                case AbstractInsnNode.JUMP_INSN:
                    abstractInsnNode = new JumpInsnNode(Opcodes.IFEQ, OpcodeUtil.getFirstLabel(methodNode.instructions));
                    break;
                case AbstractInsnNode.LABEL:
                    abstractInsnNode = new LabelNode();
                    break;
                case AbstractInsnNode.LDC_INSN:
                    abstractInsnNode = new LdcInsnNode("");
                    break;
                case AbstractInsnNode.IINC_INSN:
                    abstractInsnNode = new IincInsnNode(0, 0);
                    break;
                case AbstractInsnNode.TABLESWITCH_INSN:
                    LabelNode defaultLabel = OpcodeUtil.getFirstLabel(methodNode.instructions);
                    abstractInsnNode = new TableSwitchInsnNode(0, 0, defaultLabel, defaultLabel);
                    break;
                case AbstractInsnNode.LOOKUPSWITCH_INSN:
                    abstractInsnNode = new LookupSwitchInsnNode(OpcodeUtil.getFirstLabel(methodNode.instructions), new int[]{}, new LabelNode[]{});
                    break;
                case AbstractInsnNode.MULTIANEWARRAY_INSN:
                    abstractInsnNode = new MultiANewArrayInsnNode("", 0);
                    break;
                case AbstractInsnNode.FRAME:
                    abstractInsnNode = new FrameNode(Opcodes.F_NEW, 0, new Object[]{}, 0, new Object[]{});
                    break;
                case AbstractInsnNode.LINE:
                    abstractInsnNode = new LineNumberNode(0, OpcodeUtil.getFirstLabel(methodNode.instructions));
                    break;
                default:
                    throw new RuntimeException();
            }

            MessageUtil.confirm(new InstructionEditPanel(abstractInsnNode), LangUtil.i18n("popup.instructions.edit"), () -> {
                int anchorIndex = instructionDefaultListModel.indexOf(anchor);
                if (anchorIndex < 0) return;
                if (before) {
                    methodNode.instructions.insertBefore(anchor.getWrapper(), abstractInsnNode);
                } else {
                    methodNode.instructions.insert(anchor.getWrapper(), abstractInsnNode);
                }
                instructionDefaultListModel.add(anchorIndex + (before ? 0 : 1), new InstructionWrapper(abstractInsnNode));
            });
        });
    }
}
