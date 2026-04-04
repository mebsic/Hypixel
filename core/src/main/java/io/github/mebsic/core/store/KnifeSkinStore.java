package io.github.mebsic.core.store;

import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.manager.MongoManager;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

public class KnifeSkinStore {
    public static final String SKIN_ID_PREFIX = "mm_knife_skin_";
    public static final String DEFAULT_KNIFE_ID = "mm_knife_skin_01";
    public static final String SKIN_02_CHEST_ID = "mm_knife_skin_02";
    public static final String SKIN_03_ENDER_CHEST_ID = "mm_knife_skin_03";
    public static final String SKIN_04_IRON_BLADE_ID = "mm_knife_skin_04";
    public static final String SKIN_05_STICK_ID = "mm_knife_skin_05";
    public static final String SKIN_06_WOOD_SPADE_ID = "mm_knife_skin_06";
    public static final String SKIN_07_WOOD_AXE_ID = "mm_knife_skin_07";
    public static final String SKIN_08_GOLD_SWORD_ID = "mm_knife_skin_08";
    public static final String SKIN_09_DEAD_BUSH_ID = "mm_knife_skin_09";
    public static final String SKIN_10_SUGAR_CANE_ID = "mm_knife_skin_10";
    public static final String SKIN_11_WOOD_SPADE_ID = "mm_knife_skin_11";
    public static final String SKIN_12_BLAZE_ROD_ID = "mm_knife_skin_12";
    public static final String SKIN_13_DIAMOND_SPADE_ID = "mm_knife_skin_13";
    public static final String SKIN_14_QUARTZ_ID = "mm_knife_skin_14";
    public static final String SKIN_15_RABBIT_FOOT_ID = "mm_knife_skin_15";
    public static final String SKIN_16_GOLD_PICKAXE_ID = "mm_knife_skin_16";
    public static final String SKIN_17_LEATHER_ID = "mm_knife_skin_17";
    public static final String SKIN_18_GOLD_SPADE_ID = "mm_knife_skin_18";
    public static final String SKIN_19_COAL_ID = "mm_knife_skin_19";
    public static final String SKIN_20_FLINT_ID = "mm_knife_skin_20";
    public static final String SKIN_21_BONE_ID = "mm_knife_skin_21";
    public static final String SKIN_22_CARROT_ID = "mm_knife_skin_22";
    public static final String SKIN_23_GOLDEN_CARROT_ID = "mm_knife_skin_23";
    public static final String SKIN_24_COOKIE_ID = "mm_knife_skin_24";
    public static final String SKIN_25_DIAMOND_AXE_ID = "mm_knife_skin_25";
    public static final String SKIN_26_ROSE_ID = "mm_knife_skin_26";
    public static final String SKIN_27_PRISMARINE_CRYSTALS_ID = "mm_knife_skin_27";
    public static final String SKIN_28_STEAK_ID = "mm_knife_skin_28";
    public static final String SKIN_29_NETHER_BRICK_ID = "mm_knife_skin_29";
    public static final String SKIN_30_RAW_CHICKEN_ID = "mm_knife_skin_30";
    public static final String SKIN_31_RECORD_ID = "mm_knife_skin_31";
    public static final String SKIN_32_GOLD_PICKAXE_ID = "mm_knife_skin_32";
    public static final String SKIN_33_PRISMARINE_SHARD_ID = "mm_knife_skin_33";
    public static final String SKIN_34_GOLD_SWORD_ID = "mm_knife_skin_34";
    public static final String SKIN_35_DIAMOND_SWORD_ID = "mm_knife_skin_35";
    public static final String SKIN_36_DIAMOND_HOE_ID = "mm_knife_skin_36";
    public static final String SKIN_37_SHEARS_ID = "mm_knife_skin_37";
    public static final String SKIN_38_RAW_FISH_ID = "mm_knife_skin_38";
    public static final String SKIN_39_NETHER_WART_ID = "mm_knife_skin_39";
    public static final String SKIN_40_BREAD_ID = "mm_knife_skin_40";
    public static final String SKIN_41_BOAT_ID = "mm_knife_skin_41";
    public static final String SKIN_42_MELON_ID = "mm_knife_skin_42";
    public static final String SKIN_43_BOOK_ID = "mm_knife_skin_43";
    public static final String SKIN_44_SAPLING_ID = "mm_knife_skin_44";
    public static final String SKIN_45_GOLD_AXE_ID = "mm_knife_skin_45";
    public static final String SKIN_46_DIAMOND_PICKAXE_ID = "mm_knife_skin_46";
    public static final String SKIN_47_GOLD_SPADE_ID = "mm_knife_skin_47";
    private static final List<String> NON_PURCHASABLE_IDS = Collections.unmodifiableList(Arrays.asList(
            DEFAULT_KNIFE_ID,
            SKIN_02_CHEST_ID,
            SKIN_03_ENDER_CHEST_ID
    ));
    private static final List<SeedSkin> MURDER_MYSTERY_DEFAULT_SKINS = Collections.unmodifiableList(Arrays.asList(
            skin(DEFAULT_KNIFE_ID, "IRON_SWORD", "&aDefault Iron Sword", "common"),
            skin(SKIN_02_CHEST_ID, "CHEST", "&aRandom Knife Skin", "common"),
            skin(SKIN_03_ENDER_CHEST_ID, "ENDER_CHEST", "&aRandom Favorite Knife Skin", "common"),
            skin(SKIN_04_IRON_BLADE_ID, "STONE_SWORD", "&aCheapo Sword", "common"),
            skin(SKIN_05_STICK_ID, "IRON_SPADE", "&aShovel", "common"),
            skin(SKIN_06_WOOD_SPADE_ID, "STICK", "&aStick", "common"),
            skin(SKIN_07_WOOD_AXE_ID, "WOOD_AXE", "&aWooden Axe", "common"),
            skin(SKIN_08_GOLD_SWORD_ID, "WOOD_SWORD", "&aStake", "common"),
            skin(SKIN_09_DEAD_BUSH_ID, "DEAD_BUSH", "&aChewed Up Bush", "common"),
            skin(SKIN_10_SUGAR_CANE_ID, "SUGAR_CANE", "&aFragile Plant", "common"),
            skin(SKIN_11_WOOD_SPADE_ID, "STONE_SPADE", "&aStick with a Hat", "common"),
            skin(SKIN_12_BLAZE_ROD_ID, "BLAZE_ROD", "&aBlaze Rod", "rare"),
            skin(SKIN_13_DIAMOND_SPADE_ID, "DIAMOND_SPADE", "&aOnly the Best", "rare"),
            skin(SKIN_14_QUARTZ_ID, "QUARTZ", "&aJagged Knife", "rare"),
            skin(SKIN_15_RABBIT_FOOT_ID, "PUMPKIN_PIE", "&aPumpkin Pie", "rare"),
            skin(SKIN_16_GOLD_PICKAXE_ID, "GOLD_PICKAXE", "&aGold Digger", "rare"),
            skin(SKIN_17_LEATHER_ID, "LEATHER", "&aUnfortunately Colored Jacket", "rare"),
            skin(SKIN_18_GOLD_SPADE_ID, "NAME_TAG", "&aMouse Trap", "rare"),
            skin(SKIN_19_COAL_ID, "COAL", "&aCampfire Leftovers", "rare"),
            skin(SKIN_20_FLINT_ID, "FLINT", "&aSomewhat Sharp Rock", "rare"),
            skin(SKIN_21_BONE_ID, "BONE", "&aBig Bone", "rare"),
            skin(SKIN_22_CARROT_ID, "CARROT_ITEM", "&aRudolph's Favorite Snack", "rare"),
            skin(SKIN_23_GOLDEN_CARROT_ID, "GOLDEN_CARROT", "&aSparkly Snack", "rare"),
            skin(SKIN_24_COOKIE_ID, "COOKIE", "&aSweet Treat", "rare"),
            skin(SKIN_25_DIAMOND_AXE_ID, "DIAMOND_AXE", "&aTimber", "rare"),
            skin(SKIN_26_ROSE_ID, "DOUBLE_PLANT", "&aPrickly", "rare"),
            skin(SKIN_27_PRISMARINE_CRYSTALS_ID, "PRISMARINE_SHARD", "&aIce Shard", "rare"),
            skin(SKIN_28_STEAK_ID, "COOKED_BEEF", "&aGrilled Steak", "rare"),
            skin(SKIN_29_NETHER_BRICK_ID, "NETHER_BRICK_ITEM", "&aBloody Brick", "rare"),
            skin(SKIN_30_RAW_CHICKEN_ID, "COOKED_CHICKEN", "&aRoasted Turkey", "rare"),
            skin(SKIN_31_RECORD_ID, "RECORD_3", "&aFrisbee", "epic"),
            skin(SKIN_32_GOLD_PICKAXE_ID, "GOLD_HOE", "&aFarming Implement", "epic"),
            skin(SKIN_33_PRISMARINE_SHARD_ID, "INK_SACK", "&a10,000 Spoons", "epic"),
            skin(SKIN_34_GOLD_SWORD_ID, "GOLD_SWORD", "&aVIP Golden Sword", "epic"),
            skin(SKIN_35_DIAMOND_SWORD_ID, "DIAMOND_SWORD", "&aMVP Diamond Sword", "epic"),
            skin(SKIN_36_DIAMOND_HOE_ID, "DIAMOND_HOE", "&aThe Scythe", "epic"),
            skin(SKIN_37_SHEARS_ID, "SHEARS", "&aShears", "epic"),
            skin(SKIN_38_RAW_FISH_ID, "RAW_FISH", "&aSalmon", "legendary"),
            skin(SKIN_39_NETHER_WART_ID, "INK_SACK", "&aRudolph's Nose", "legendary"),
            skin(SKIN_40_BREAD_ID, "BREAD", "&aFreshly Frozen Baguette", "legendary"),
            skin(SKIN_41_BOAT_ID, "BOAT", "&aEaster Basket", "legendary"),
            skin(SKIN_42_MELON_ID, "SPECKLED_MELON", "&aGlistering Melon", "legendary"),
            skin(SKIN_43_BOOK_ID, "BOOK", "&aGrimoire", "legendary"),
            skin(SKIN_44_SAPLING_ID, "SAPLING", "&aEarthen Dagger", "legendary"),
            skin(SKIN_45_GOLD_AXE_ID, "GOLD_AXE", "&aShred", "legendary"),
            skin(SKIN_46_DIAMOND_PICKAXE_ID, "DIAMOND_PICKAXE", "&aDouble Death Scythe", "legendary"),
            skin(SKIN_47_GOLD_SPADE_ID, "GOLD_SPADE", "&aGold Spray Painted Shovel", "legendary")
    ));

    private final MongoCollection<Document> collection;

    public KnifeSkinStore(MongoManager mongo) {
        this.collection = mongo.getKnifeSkins();
    }

    public List<KnifeSkinDefinition> loadAll() {
        List<KnifeSkinDefinition> skins = new ArrayList<>();
        for (Document doc : collection.find(eq(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE))) {
            if (!supportsMurderMystery(doc)) {
                continue;
            }
            KnifeSkinDefinition def = fromDocument(doc);
            if (def != null && def.getId() != null) {
                skins.add(def);
            }
        }
        return skins;
    }

    public void ensureDefault() {
        for (SeedSkin seed : MURDER_MYSTERY_DEFAULT_SKINS) {
            if (seed == null) {
                continue;
            }
            String rarity = normalizeRarity(seed.rarity);
            Document update = new Document("id", seed.id)
                    .append(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE)
                    .append("material", seed.material)
                    .append("displayName", seed.displayName)
                    .append("description", "")
                    .append("cost", defaultCostForRarity(rarity))
                    .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE)
                    .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, rarity);
            collection.updateOne(
                    and(eq("id", seed.id), eq(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE)),
                    new Document("$setOnInsert", update),
                    new UpdateOptions().upsert(true)
            );
        }
        collection.updateOne(
                skinFilter(DEFAULT_KNIFE_ID),
                new Document("$set", new Document("displayName", "&aDefault Iron Sword")
                        .append("description", "Selecting this option disables your Knife Skin.")
                        .append("cost", 0)
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, normalizeRarity(KnifeSkinDefinition.DEFAULT_RARITY))
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_02_CHEST_ID),
                new Document("$set", new Document("material", "CHEST")
                        .append("displayName", "&aRandom Knife Skin")
                        .append("description", "")
                        .append("cost", 0)
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "common")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_03_ENDER_CHEST_ID),
                new Document("$set", new Document("material", "ENDER_CHEST")
                        .append("displayName", "&aRandom Favorite Knife Skin")
                        .append("description", "")
                        .append("cost", 0)
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "common")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_04_IRON_BLADE_ID),
                new Document("$set", new Document("material", "STONE_SWORD")
                        .append("displayName", "&aCheapo Sword")
                        .append("description", "Nothing wrong with simple living.")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_05_STICK_ID),
                new Document("$set", new Document("material", "IRON_SPADE")
                        .append("displayName", "&aShovel")
                        .append("description", "WHACK EM.")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_08_GOLD_SWORD_ID),
                new Document("$set", new Document("material", "WOOD_SWORD")
                        .append("displayName", "&aStake")
                        .append("description", "Can also be used to support plants.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "common")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_06_WOOD_SPADE_ID),
                new Document("$set", new Document("material", "STICK")
                        .append("displayName", "&aStick")
                        .append("description", "A primitive man's weapon.")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_11_WOOD_SPADE_ID),
                new Document("$set", new Document("material", "STONE_SPADE")
                        .append("displayName", "&aStick with a Hat")
                        .append("description", "A gentleman's weapon.")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_07_WOOD_AXE_ID),
                new Document("$set", new Document("displayName", "&aWooden Axe")
                        .append("description", "Might as well just punch the tree.")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_09_DEAD_BUSH_ID),
                new Document("$set", new Document("displayName", "&aChewed Up Bush")
                        .append("description", "Who's been gnawing on this poor bush?")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_10_SUGAR_CANE_ID),
                new Document("$set", new Document("displayName", "&aFragile Plant")
                        .append("description", "It's more likely to split open on contact rather than do any harm.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "common")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_12_BLAZE_ROD_ID),
                new Document("$set", new Document("displayName", "&aBlaze Rod")
                        .append("description", "Some say this is a blaze's spine.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_13_DIAMOND_SPADE_ID),
                new Document("$set", new Document("displayName", "&aOnly the Best")
                        .append("description", "The most premium weapon.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_14_QUARTZ_ID),
                new Document("$set", new Document("displayName", "&aJagged Knife")
                        .append("description", "Sharpened and ready.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_15_RABBIT_FOOT_ID),
                new Document("$set", new Document("material", "PUMPKIN_PIE")
                        .append("displayName", "&aPumpkin Pie")
                        .append("description", "Delicious and deadly.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_16_GOLD_PICKAXE_ID),
                new Document("$set", new Document("displayName", "&aGold Digger")
                        .append("description", "$$$.")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_17_LEATHER_ID),
                new Document("$set", new Document("displayName", "&aUnfortunately Colored Jacket")
                        .append("description", "This thing could use a wash or two. Probably three if we're being honest.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_18_GOLD_SPADE_ID),
                new Document("$set", new Document("material", "NAME_TAG")
                        .append("displayName", "&aMouse Trap")
                        .append("description", "Gotta catch that rat.")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_19_COAL_ID),
                new Document("$set", new Document("material", "COAL")
                        .append("displayName", "&aCampfire Leftovers")
                        .append("description", "A surprisingly good bug repellent.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_20_FLINT_ID),
                new Document("$set", new Document("displayName", "&aSomewhat Sharp Rock")
                        .append("description", "You could've just picked it up off the ground.")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_21_BONE_ID),
                new Document("$set", new Document("displayName", "&aBig Bone")
                        .append("description", "Taken from one of your past victims.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_22_CARROT_ID),
                new Document("$set", new Document("displayName", "&aRudolph's Favorite Snack")
                        .append("description", "Use as weapon, not as food.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_23_GOLDEN_CARROT_ID),
                new Document("$set", new Document("displayName", "&aSparkly Snack")
                        .append("description", "Looks better than it tastes.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_24_COOKIE_ID),
                new Document("$set", new Document("displayName", "&aSweet Treat")
                        .append("description", "Kids will never want to visit your house again.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_25_DIAMOND_AXE_ID),
                new Document("$set", new Document("displayName", "&aTimber")
                        .append("description", "Cutting trees in style.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_26_ROSE_ID),
                new Document("$set", new Document("material", "DOUBLE_PLANT")
                        .append("displayName", "&aPrickly")
                        .append("description", "Quite sharp to the touch.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_27_PRISMARINE_CRYSTALS_ID),
                new Document("$set", new Document("material", "PRISMARINE_SHARD")
                        .append("displayName", "&aIce Shard")
                        .append("description", "Also works great as a popsicle!")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_28_STEAK_ID),
                new Document("$set", new Document("material", "COOKED_BEEF")
                        .append("displayName", "&aGrilled Steak")
                        .append("description", "Straight off the grill, but also good for vampires.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_29_NETHER_BRICK_ID),
                new Document("$set", new Document("material", "NETHER_BRICK_ITEM")
                        .append("displayName", "&aBloody Brick")
                        .append("description", "This brick wasn't allowed to be a part of the yellow brick road.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_30_RAW_CHICKEN_ID),
                new Document("$set", new Document("material", "COOKED_CHICKEN")
                        .append("displayName", "&aRoasted Turkey")
                        .append("description", "Covered in lethal amounts of grease and oil.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "rare")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_31_RECORD_ID),
                new Document("$set", new Document("material", "RECORD_3")
                        .append("displayName", "&aFrisbee")
                        .append("description", "Don't try and catch this.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "epic")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_32_GOLD_PICKAXE_ID),
                new Document("$set", new Document("material", "GOLD_HOE")
                        .append("displayName", "&aFarming Implement")
                        .append("description", "It's useful for farming!")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "epic")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_33_PRISMARINE_SHARD_ID),
                new Document("$set", new Document("material", "INK_SACK")
                        .append("displayName", "&a10,000 Spoons")
                        .append("description", "Pretty ironic if you own this one.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "epic")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_34_GOLD_SWORD_ID),
                new Document("$set", new Document("material", "GOLD_SWORD")
                        .append("displayName", "&aVIP Golden Sword")
                        .append("description", "Show off your VIP rank with this fancy golden sword.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "epic")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_35_DIAMOND_SWORD_ID),
                new Document("$set", new Document("material", "DIAMOND_SWORD")
                        .append("displayName", "&aMVP Diamond Sword")
                        .append("description", "Show off your MVP rank with this marvelous diamond sword.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "epic")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_36_DIAMOND_HOE_ID),
                new Document("$set", new Document("material", "DIAMOND_HOE")
                        .append("displayName", "&aThe Scythe")
                        .append("description", "Slice your enemies... and hay.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "epic")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_37_SHEARS_ID),
                new Document("$set", new Document("material", "SHEARS")
                        .append("displayName", "&aShears")
                        .append("description", "Not suitable for children.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "epic")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_38_RAW_FISH_ID),
                new Document("$set", new Document("material", "RAW_FISH")
                        .append("displayName", "&aSalmon")
                        .append("description", "Something smells fishy here...")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_39_NETHER_WART_ID),
                new Document("$set", new Document("material", "INK_SACK")
                        .append("displayName", "&aRudolph's Nose")
                        .append("description", "How'd you even get this?")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_40_BREAD_ID),
                new Document("$set", new Document("material", "BREAD")
                        .append("displayName", "&aFreshly Frozen Baguette")
                        .append("description", "Recently taken out of the freezer, it's still thawing.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_41_BOAT_ID),
                new Document("$set", new Document("material", "BOAT")
                        .append("displayName", "&aEaster Basket")
                        .append("description", "Good for collecting!")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_42_MELON_ID),
                new Document("$set", new Document("material", "SPECKLED_MELON")
                        .append("displayName", "&aGlistering Melon")
                        .append("description", "Often misspelled as \"glistening\".")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_43_BOOK_ID),
                new Document("$set", new Document("material", "BOOK")
                        .append("displayName", "&aGrimoire")
                        .append("description", "Why do dark magic with it? Smack people on the head with it instead, works just as well.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_44_SAPLING_ID),
                new Document("$set", new Document("material", "SAPLING")
                        .append("displayName", "&aEarthen Dagger")
                        .append("description", "Mother Earth's weapon of choice.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_45_GOLD_AXE_ID),
                new Document("$set", new Document("material", "GOLD_AXE")
                        .append("displayName", "&aShred")
                        .append("description", "Shred the innocents to pieces.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_46_DIAMOND_PICKAXE_ID),
                new Document("$set", new Document("material", "DIAMOND_PICKAXE")
                        .append("displayName", "&aDouble Death Scythe")
                        .append("description", "Double the blades, double the reaping.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateOne(
                skinFilter(SKIN_47_GOLD_SPADE_ID),
                new Document("$set", new Document("material", "GOLD_SPADE")
                        .append("displayName", "&aGold Spray Painted Shovel")
                        .append("description", "This Golden Shovel was spray painted gold, for some reason.")
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, "legendary")
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateMany(
                new Document(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE)
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, new Document("$exists", false)),
                new Document("$set", new Document(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
        );
        collection.updateMany(
                new Document(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE)
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, new Document("$exists", false)),
                new Document("$set", new Document(MongoManager.MURDER_MYSTERY_RARITY_FIELD, normalizeRarity(KnifeSkinDefinition.DEFAULT_RARITY)))
        );
        collection.updateMany(
                new Document(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE)
                        .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE)
                        .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, new Document("$not",
                                new Document("$regex", "^(common|rare|epic|legendary)$").append("$options", "i"))),
                new Document("$set", new Document(MongoManager.MURDER_MYSTERY_RARITY_FIELD, KnifeSkinDefinition.DEFAULT_RARITY))
        );
        updatePurchasableCostsByProgression();
    }

    private KnifeSkinDefinition fromDocument(Document doc) {
        if (doc == null) {
            return null;
        }
        String id = normalizeKnifeSkinId(doc.getString("id"));
        String material = doc.getString("material");
        String displayName = doc.getString("displayName");
        String description = doc.getString("description");
        Number costValue = doc.get("cost", Number.class);
        int cost = costValue == null ? 0 : costValue.intValue();
        String rarity = normalizeRarity(doc.getString(MongoManager.MURDER_MYSTERY_RARITY_FIELD));
        if (rarity.isEmpty()) {
            rarity = KnifeSkinDefinition.DEFAULT_RARITY;
        }
        return new KnifeSkinDefinition(id, material, displayName, description, cost, rarity);
    }

    private boolean supportsMurderMystery(Document doc) {
        if (doc == null) {
            return false;
        }
        String gameType = normalize(doc.getString(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD)).replace("_", "");
        return gameType.isEmpty() || gameType.equals(MongoManager.MURDER_MYSTERY_GAME_TYPE);
    }

    public static String normalizeKnifeSkinId(String value) {
        String normalized = normalizeStatic(value);
        if (normalized.isEmpty()) {
            return "";
        }
        int number = skinNumber(normalized);
        if (number > 0) {
            return idByNumber(number);
        }
        return normalized;
    }

    public static int skinNumber(String value) {
        String normalized = normalizeStatic(value);
        if (normalized.isEmpty()) {
            return -1;
        }
        if (normalized.startsWith(SKIN_ID_PREFIX)) {
            String numberToken = normalized.substring(SKIN_ID_PREFIX.length());
            return parsePositiveInt(numberToken);
        }
        return -1;
    }

    private static String idByNumber(int number) {
        if (number <= 0) {
            return "";
        }
        if (number < 10) {
            return SKIN_ID_PREFIX + "0" + number;
        }
        return SKIN_ID_PREFIX + number;
    }

    private static int parsePositiveInt(String value) {
        if (!isDigits(value)) {
            return -1;
        }
        try {
            int number = Integer.parseInt(value);
            return number > 0 ? number : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeStatic(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return normalizeStatic(value);
    }

    private static SeedSkin skin(String id, String material, String displayName, String rarity) {
        return new SeedSkin(id, material, displayName, rarity);
    }

    private void updateCostsByRarity(String rarity) {
        String normalized = normalizeRarity(rarity);
        int cost = defaultCostForRarity(normalized);
        Document filter = new Document(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE)
                .append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE)
                .append(MongoManager.MURDER_MYSTERY_RARITY_FIELD, new Document("$regex", "^" + normalized + "$").append("$options", "i"))
                .append("id", new Document("$nin", NON_PURCHASABLE_IDS));
        collection.updateMany(filter, new Document("$set", new Document("cost", cost)));
    }

    private void updatePurchasableCostsByProgression() {
        // Common: skins 04..11
        updateSequentialCosts(4, 11, 250000, 100000);
        // Rare: skins 12..30 (starts at Blaze Rod)
        updateSequentialCosts(12, 30, 1500000, 175000);
        // Epic: skins 31..37
        updateSequentialCosts(31, 37, 5750000, 500000);
        // Legendary: skins 38..47 (starts at 9.5M)
        updateSequentialCosts(38, 47, 9500000, 500000);
    }

    private void updateSequentialCosts(int startInclusive, int endInclusive, int baseCost, int step) {
        int from = Math.max(0, startInclusive);
        int to = Math.max(from, endInclusive);
        int safeBase = Math.max(0, baseCost);
        int safeStep = Math.max(0, step);
        for (int number = from; number <= to; number++) {
            String id = idFor(number);
            if (id.isEmpty()) {
                continue;
            }
            int offset = number - from;
            int cost = safeBase + (offset * safeStep);
            collection.updateOne(
                    skinFilter(id),
                    new Document("$set", new Document("cost", cost).append(MongoManager.MURDER_MYSTERY_GAME_TYPE_FIELD, MongoManager.MURDER_MYSTERY_GAME_TYPE))
            );
        }
    }

    private Document skinFilter(String id) {
        return new Document("id", id).append(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE);
    }

    private String idFor(int number) {
        switch (number) {
            case 1:
                return DEFAULT_KNIFE_ID;
            case 2:
                return SKIN_02_CHEST_ID;
            case 3:
                return SKIN_03_ENDER_CHEST_ID;
            case 4:
                return SKIN_04_IRON_BLADE_ID;
            case 5:
                return SKIN_05_STICK_ID;
            case 6:
                return SKIN_06_WOOD_SPADE_ID;
            case 7:
                return SKIN_07_WOOD_AXE_ID;
            case 8:
                return SKIN_08_GOLD_SWORD_ID;
            case 9:
                return SKIN_09_DEAD_BUSH_ID;
            case 10:
                return SKIN_10_SUGAR_CANE_ID;
            case 11:
                return SKIN_11_WOOD_SPADE_ID;
            case 12:
                return SKIN_12_BLAZE_ROD_ID;
            case 13:
                return SKIN_13_DIAMOND_SPADE_ID;
            case 14:
                return SKIN_14_QUARTZ_ID;
            case 15:
                return SKIN_15_RABBIT_FOOT_ID;
            case 16:
                return SKIN_16_GOLD_PICKAXE_ID;
            case 17:
                return SKIN_17_LEATHER_ID;
            case 18:
                return SKIN_18_GOLD_SPADE_ID;
            case 19:
                return SKIN_19_COAL_ID;
            case 20:
                return SKIN_20_FLINT_ID;
            case 21:
                return SKIN_21_BONE_ID;
            case 22:
                return SKIN_22_CARROT_ID;
            case 23:
                return SKIN_23_GOLDEN_CARROT_ID;
            case 24:
                return SKIN_24_COOKIE_ID;
            case 25:
                return SKIN_25_DIAMOND_AXE_ID;
            case 26:
                return SKIN_26_ROSE_ID;
            case 27:
                return SKIN_27_PRISMARINE_CRYSTALS_ID;
            case 28:
                return SKIN_28_STEAK_ID;
            case 29:
                return SKIN_29_NETHER_BRICK_ID;
            case 30:
                return SKIN_30_RAW_CHICKEN_ID;
            case 31:
                return SKIN_31_RECORD_ID;
            case 32:
                return SKIN_32_GOLD_PICKAXE_ID;
            case 33:
                return SKIN_33_PRISMARINE_SHARD_ID;
            case 34:
                return SKIN_34_GOLD_SWORD_ID;
            case 35:
                return SKIN_35_DIAMOND_SWORD_ID;
            case 36:
                return SKIN_36_DIAMOND_HOE_ID;
            case 37:
                return SKIN_37_SHEARS_ID;
            case 38:
                return SKIN_38_RAW_FISH_ID;
            case 39:
                return SKIN_39_NETHER_WART_ID;
            case 40:
                return SKIN_40_BREAD_ID;
            case 41:
                return SKIN_41_BOAT_ID;
            case 42:
                return SKIN_42_MELON_ID;
            case 43:
                return SKIN_43_BOOK_ID;
            case 44:
                return SKIN_44_SAPLING_ID;
            case 45:
                return SKIN_45_GOLD_AXE_ID;
            case 46:
                return SKIN_46_DIAMOND_PICKAXE_ID;
            case 47:
                return SKIN_47_GOLD_SPADE_ID;
            default:
                return "";
        }
    }

    private String normalizeRarity(String rarity) {
        String normalized = normalize(rarity);
        if (normalized.equals("legendary")) {
            return "legendary";
        }
        if (normalized.equals("epic")) {
            return "epic";
        }
        if (normalized.equals("rare")) {
            return "rare";
        }
        return KnifeSkinDefinition.DEFAULT_RARITY;
    }

    private int defaultCostForRarity(String rarity) {
        String normalized = normalizeRarity(rarity);
        if (normalized.equals("legendary")) {
            return 9500000;
        }
        if (normalized.equals("epic")) {
            return 5750000;
        }
        if (normalized.equals("rare")) {
            return 1500000;
        }
        return 250000;
    }

    private static final class SeedSkin {
        private final String id;
        private final String material;
        private final String displayName;
        private final String rarity;

        private SeedSkin(String id, String material, String displayName, String rarity) {
            this.id = id == null ? "" : id;
            this.material = material == null ? "IRON_SWORD" : material;
            this.displayName = displayName == null ? "" : displayName;
            this.rarity = rarity == null ? KnifeSkinDefinition.DEFAULT_RARITY : rarity;
        }
    }
}
