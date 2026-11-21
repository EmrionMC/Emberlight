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

/** The pack's id maps resolved against the registries; immutable after build, so meshing threads read it lock-free. Unmapped = -1 (Iris). */
public final class BlockIdTable {
	public static final Identifier CURRENT_PLAYER = Identifier.fromNamespaceAndPath("minecraft", "current_player");
	/** Iris's special entity.properties names (entity_render_context mixins): converting zombie villagers, entity fire, name tags. */
	public static final Identifier CONVERTING_VILLAGER = Identifier.fromNamespaceAndPath("minecraft", "zombie_villager_converting"),
			ENTITY_FLAME = Identifier.fromNamespaceAndPath("minecraft", "entity_flame"), NAME_TAG = Identifier.fromNamespaceAndPath("minecraft", "name_tag");
	/** item.properties name Iris gives the player's cape (MixinCapeLayer). */
	public static final Identifier PLAYER_CAPE = Identifier.fromNamespaceAndPath("minecraft", "player_cape");
	public static final BlockIdTable EMPTY = new BlockIdTable(null, new Reference2IntOpenHashMap<>(), new Reference2IntOpenHashMap<>(),
			new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>());

	final Manifest.@Nullable IdMaps source;
	private final Reference2IntOpenHashMap<BlockState> blocks;
	private final Reference2IntOpenHashMap<EntityType<?>> entities;
	private final Object2IntOpenHashMap<Identifier> items, entityNames;

	private BlockIdTable(Manifest.@Nullable IdMaps source, Reference2IntOpenHashMap<BlockState> blocks,
			Reference2IntOpenHashMap<EntityType<?>> entities, Object2IntOpenHashMap<Identifier> items, Object2IntOpenHashMap<Identifier> entityNames) {
		this.source = source;
		this.blocks = blocks;
		this.entities = entities;
		this.items = items;
		this.entityNames = entityNames;
		blocks.defaultReturnValue(-1);
		entities.defaultReturnValue(-1);
		items.defaultReturnValue(-1);
		entityNames.defaultReturnValue(-1);
	}

	/** Resolves the id maps against the registries (render thread; needs bound tags for {@code #tag} matchers). */
	public static BlockIdTable build(Manifest.IdMaps maps) {
		Reference2IntOpenHashMap<BlockState> blocks = new Reference2IntOpenHashMap<>();
		assign(maps.blocks(), REGISTRY, blocks);
		Reference2IntOpenHashMap<EntityType<?>> entities = new Reference2IntOpenHashMap<>();
		Object2IntOpenHashMap<Identifier> entityNames = new Object2IntOpenHashMap<>();
		for (Manifest.IdEntry e : maps.entities()) {
			for (String name : e.match()) {
				Identifier id = Identifier.tryParse(name);
				if (id == null) continue;
				entityNames.put(id, e.id());
				BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(t -> entities.put(t, e.id()));
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
		return new BlockIdTable(maps, blocks, entities, items, entityNames);
	}

	public int block(BlockState state) { return blocks.getInt(state); }

	public int entity(EntityType<?> type) { return entities.getInt(type); }

	/** entity.properties id of {@code minecraft:current_player}, or −1 if the pack has none (then the player type's id applies). */
	public int currentPlayer() { return entityNames.getInt(CURRENT_PLAYER); }

	/** entity.properties id of any name, entity type or special (Iris entityIds.applyAsInt), −1 if unmapped. */
	public int entityName(Identifier name) { return entityNames.getInt(name); }

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

	/** Iris BlockMaterialMapping: first match wins; a predicate on a missing property is ignored. */
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
