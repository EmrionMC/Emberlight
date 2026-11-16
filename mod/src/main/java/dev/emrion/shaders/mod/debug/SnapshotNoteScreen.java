package dev.emrion.shaders.mod.debug;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** After F9: one line for what looks wrong. Enter saves it into the snapshot, Esc skips. */
final class SnapshotNoteScreen extends Screen {
	private final Path dir;
	private EditBox box;

	SnapshotNoteScreen(Path dir) {
		super(Component.literal("Snapshot note"));
		this.dir = dir;
	}

	@Override
	protected void init() {
		box = new EditBox(font, width / 2 - 200, height / 2 - 10, 400, 20, Component.literal("Note"));
		box.setMaxLength(500);
		box.setHint(Component.literal("What looks wrong? (Enter saves, Esc skips)"));
		addRenderableWidget(box);
		setInitialFocus(box);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) {
			Snapshot.addNote(minecraft, dir, box.getValue());
			onClose();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, Component.literal("Snapshot saved - describe the problem"), width / 2, height / 2 - 26, 0xFFFFFFFF);
	}
}
