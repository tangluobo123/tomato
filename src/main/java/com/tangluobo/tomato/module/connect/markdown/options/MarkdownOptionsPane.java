/*
 * Copyright (c) 2015 Karl Tauber <karl at jformdesigner dot com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  o Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  o Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.tangluobo.tomato.module.connect.markdown.options;

import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.text.Font;
import com.tangluobo.tomato.module.connect.markdown.Messages;
import com.tangluobo.tomato.module.connect.markdown.options.Options.RendererType;
import org.tbee.javafx.scene.layout.fxml.MigPane;

/**
 * Markdown options pane
 *
 * @author Karl Tauber
 */
class MarkdownOptionsPane
	extends MigPane
{
	MarkdownOptionsPane() {
		initComponents();

		markdownRendererChoiceBox.getItems().addAll(RendererType.values());
		markdownRendererChoiceBox.getSelectionModel().selectedItemProperty().addListener((ob, o, n) -> {
			markdownExtensionsPane.rendererTypeChanged(n);
		});

		markdownExtensionsLabel.setFont(Font.font(16));
	}

	void load() {
		markdownRendererChoiceBox.getSelectionModel().select(Options.getMarkdownRenderer());
	}

	void save() {
		Options.setMarkdownRenderer(markdownRendererChoiceBox.getSelectionModel().getSelectedItem());
		markdownExtensionsPane.save();
	}

	private void initComponents() {
		markdownRendererLabel = new Label();
		markdownRendererChoiceBox = new ChoiceBox<>();
		markdownExtensionsLabel = new Label();
		markdownExtensionsPane = new MarkdownExtensionsPane();

		//======== this ========
		setLayout("insets dialog");
		setCols(
			"[]" +
			"[grow,fill]");
		setRows(
			"[]para" +
			"[]" +
			"[grow,fill]");

		//---- markdownRendererLabel ----
		markdownRendererLabel.setText(Messages.get("MarkdownOptionsPane.markdownRendererLabel.text"));
		markdownRendererLabel.setLabelFor(markdownRendererChoiceBox);
		markdownRendererLabel.setMnemonicParsing(true);
		add(markdownRendererLabel, "cell 0 0");
		add(markdownRendererChoiceBox, "cell 1 0,alignx left,growx 0");

		//---- markdownExtensionsLabel ----
		markdownExtensionsLabel.setText(Messages.get("MarkdownOptionsPane.markdownExtensionsLabel.text"));
		add(markdownExtensionsLabel, "cell 0 1 2 1");
		add(markdownExtensionsPane, "pad 0 indent 0 0,cell 0 2 2 1");
	}

	private Label markdownRendererLabel;
	private ChoiceBox<com.tangluobo.tomato.module.connect.markdown.options.Options.RendererType> markdownRendererChoiceBox;
	private Label markdownExtensionsLabel;
	private MarkdownExtensionsPane markdownExtensionsPane;
}
