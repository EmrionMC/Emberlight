package dev.emrion.shaders.mod.vertex;

import dev.emrion.shaders.format.Manifest;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * The pack's id maps (FORMAT.md §8) resolved once against the registries: block state → block.properties id (also used for
 * block entities and block items), entity type → entity.properties id, item model id → item.properties id. Immutable after
 * {@link #build}, so chunk-meshing worker threads read it without locks. Unmapped = −1, as in Iris.
 *
 * <p>Tag matchers ({@code #ns:tag}) resolve against the tags bound at build time; {@link EmrionVertexFormats} rebuilds the
 * table when the client receives tags.
 */
public final class BlockIdTable {
	public static final Identifier CURRENT_PLAYER = Identifier.fromNamespaceAndPath("minecraft", "current_player");
	public static final BlockIdTable EMPTY = new BlockIdTable(null, new Reference2IntOpenHashMap<>(), new Reference2IntOpenHashMap<>(),
			new Object2IntOpenHashMap<>(), -1);

	final Manifest.@Nullable IdMaps source;
	private final Reference2IntOpenHashMap<BlockState> blocks;
	private final Reference2IntOpenHashMap<EntityType<?>> entities;
	private final Object2IntOpenHashMap<Identifier> items;
	private final int currentPlayer;

	private BlockIdTable(Manifest.@Nullable IdMaps source, Reference2IntOpenHashMap<BlockState> blocks,
			Reference2IntOpenHashMap<EntityType<?>> entities, Object2IntOpenHashMap<Identifier> items, int currentPlayer) {
		this.source = source;
		this.blocks = blocks;
		this.entities = entities;
		this.items = items;
		this.currentPlayer = currentPlayer;
		blocks.defaultReturnValue(-1);
		entities.defaultReturnValue(-1);
		items.defaultReturnValue(-1);
	}

	/** Resolves the id maps against the registries (render thread; needs bound tags for {@code #tag} matchers). */
	public static BlockIdTable build(Manifest.IdMaps maps) {
		Reference2IntOpenHashMap<BlockState> blocks = new Reference2IntOpenHashMap<>();
		assign(maps.blocks(), REGISTRY, blocks);
		Reference2IntOpenHashMap<EntityType<?>> entities = new Reference2IntOpenHashMap<>();
		int player = -1;
		for (Manifest.IdEntry e : maps.entities()) {
			for (String name : e.match()) {
				Identifier id = Identifier.tryParse(name);
				if (id == null) continue;
				if (id.equals(CURRENT_PLAYER)) player = e.id();
				else BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(t -> entities.put(t, e.id()));
			}
		}
		Object2IntOpenHashMap<Identifier> items = new Object2IntOpenHashMap<>();
		for (Manifest.IdEntry e : maps.items()) {
			for (String name : e.match()) {
				Identifier id = Identifier.tryParse(name);
				if (id != null) items.put(id, e.id());
			}
		}
		blocks.trim();
		return new BlockIdTable(maps, blocks, entities, items, player);
	}

	public int block(BlockState state) { return blocks.getInt(state); }

	public int entity(EntityType<?> type) { return entities.getInt(type); }

	/** entity.properties id of {@code minecraft:current_player}, or −1 if the pack has none (then the player type's id applies). */
	public int currentPlayer() { return currentPlayer; }

	/** By the item's model id (ITEM_MODEL component), as Iris does. */
	public int item(Identifier modelId) { return items.getInt(modelId); }

	public int blockCount() { return blocks.size(); }

	public boolean hasTags() {
		if (source == null) return false;
		for (Manifest.IdEntry e : source.blocks()) for (String m : e.match()) if (m.startsWith("#")) return true;
		return false;
	}

	// ---- matcher (generic so it is testable without a bootstrapped registry) ------------------------------------------

	/** What the matcher needs from a registry. */
	interface States<S> {
		/** All states of block {@code ns:name}; empty if unknown (or air, which Iris skips). */
		List<S> block(String id);

		/** All states of all blocks in tag {@code ns:name}; empty if the tag is unknown. */
		List<S> tag(String id);

		/** Serialized value of property {@code key} in {@code state}, or null if its block has no such property. */
		@Nullable String property(S state, String key);
	}

	/**
	 * Iris BlockMaterialMapping: for each matcher in list order, every state that satisfies its {@code k=v} predicates gets
	 * the id unless an earlier matcher claimed it (first match wins). A predicate on a property the block lacks is ignored.
	 */
	static <S> void assign(List<Manifest.IdEntry> entries, States<S> states, Map<S, Integer> out) {
		for (Manifest.IdEntry e : entries) {
			for (String m : e.match()) {
				boolean tag = m.startsWith("#");
				String[] parts = (tag ? m.substring(1) : m).split(":");
				if (parts.length < 2) continue;
				Map<String, String> predicates = new LinkedHashMap<>();
				for (int i = 2; i < parts.length; i++) {
					String[] kv = parts[i].split("=", 2);
					if (kv.length == 2) predicates.put(kv[0], kv[1]);
				}
				String id = parts[0] + ":" + parts[1];
				for (S s : tag ? states.tag(id) : states.block(id)) {
					if (matches(s, predicates, states)) out.putIfAbsent(s, e.id());
				}
			}
		}
	}

	private static <S> boolean matches(S state, Map<String, String> predicates, States<S> states) {
		for (var p : predicates.entrySet()) {
			String actual = states.property(state, p.getKey());
			if (actual != null && !actual.equals(p.getValue())) return false;
		}
		return true;
	}

	private static final States<BlockState> REGISTRY = new States<>() {
		@Override
		public List<BlockState> block(String id) {
			Identifier key = Identifier.tryParse(id);
			if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return List.of();
			Block block = BuiltInRegistries.BLOCK.getValue(key);
			return block == Blocks.AIR ? List.of() : block.getStateDefinition().getPossibleStates();
		}

		@Override
		public List<BlockState> tag(String id) {
			Identifier key = Identifier.tryParse(id);
			if (key == null) return List.of();
			List<BlockState> all = new ArrayList<>();
			BuiltInRegistries.BLOCK.get(TagKey.create(Registries.BLOCK, key)).ifPresent(set -> {
				for (Holder<Block> h : set) all.addAll(h.value().getStateDefinition().getPossibleStates());
			});
			return all;
		}

		@Override
		@SuppressWarnings({"rawtypes", "unchecked"})
		public @Nullable String property(BlockState state, String key) {
			Property p = state.getBlock().getStateDefinition().getProperty(key);
			return p == null ? null : p.getName(state.getValue(p));
		}
	};
}
