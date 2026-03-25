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

import static com.mongodb.client.model.Filters.eq;

public class KnifeSkinStore {
    private static final String GAME_TYPE_FIELD = "gameType";
    private static final String GAME_TYPE_MURDER_MYSTERY = "murdermystery";
    private static final String RARITY_FIELD = "rarity";
    private static final List<String> NON_PURCHASABLE_IDS = Collections.unmodifiableList(Arrays.asList(
            "iron_sword",
            "mm_skin_02_chest",
            "mm_skin_03_ender_chest"
    ));
    private static final List<SeedSkin> MURDER_MYSTERY_DEFAULT_SKINS = Collections.unmodifiableList(Arrays.asList(
            skin("iron_sword", "IRON_SWORD", "&aDefault Iron Sword", "common"),
            skin("mm_skin_02_chest", "CHEST", "&aRandom Knife Skin", "common"),
            skin("mm_skin_03_ender_chest", "ENDER_CHEST", "&aRandom Favorite Knife Skin", "common"),
            skin("mm_skin_04_iron_blade", "STONE_SWORD", "&aCheapo Sword", "common"),
            skin("mm_skin_05_stick", "IRON_SPADE", "&aShovel", "common"),
            skin("mm_skin_06_wood_spade", "STICK", "&aStick", "common"),
            skin("mm_skin_07_wood_axe", "WOOD_AXE", "&aWooden Axe", "common"),
            skin("mm_skin_08_gold_sword", "WOOD_SWORD", "&aStake", "common"),
            skin("mm_skin_09_dead_bush", "DEAD_BUSH", "&aChewed Up Bush", "common"),
            skin("mm_skin_10_sugar_cane", "SUGAR_CANE", "&aFragile Plant", "common"),
            skin("mm_skin_11_wood_spade", "STONE_SPADE", "&aStick with a Hat", "common"),
            skin("mm_skin_12_blaze_rod", "BLAZE_ROD", "&aBlaze Rod", "rare"),
            skin("mm_skin_13_diamond_spade", "DIAMOND_SPADE", "&aOnly the Best", "rare"),
            skin("mm_skin_14_quartz", "QUARTZ", "&aJagged Knife", "rare"),
            skin("mm_skin_15_rabbit_foot", "PUMPKIN_PIE", "&aPumpkin Pie", "rare"),
            skin("mm_skin_16_gold_pickaxe", "GOLD_PICKAXE", "&aGold Digger", "rare"),
            skin("mm_skin_17_leather", "LEATHER", "&aUnfortunately Colored Jacket", "rare"),
            skin("mm_skin_18_gold_spade", "NAME_TAG", "&aMouse Trap", "rare"),
            skin("mm_skin_19_coal", "COAL", "&aCampfire Leftovers", "rare"),
            skin("mm_skin_20_flint", "FLINT", "&aSomewhat Sharp Rock", "rare"),
            skin("mm_skin_21_bone", "BONE", "&aBig Bone", "rare"),
            skin("mm_skin_22_carrot", "CARROT_ITEM", "&aRudolph's Favorite Snack", "rare"),
            skin("mm_skin_23_golden_carrot", "GOLDEN_CARROT", "&aSparkly Snack", "rare"),
            skin("mm_skin_24_cookie", "COOKIE", "&aSweet Treat", "rare"),
            skin("mm_skin_25_diamond_axe", "DIAMOND_AXE", "&aTimber", "rare"),
            skin("mm_skin_26_rose", "DOUBLE_PLANT", "&aPrickly", "rare"),
            skin("mm_skin_27_prismarine_crystals", "PRISMARINE_SHARD", "&aIce Shard", "rare"),
            skin("mm_skin_28_steak", "COOKED_BEEF", "&aGrilled Steak", "rare"),
            skin("mm_skin_29_nether_brick", "NETHER_BRICK_ITEM", "&aBloody Brick", "rare"),
            skin("mm_skin_30_raw_chicken", "COOKED_CHICKEN", "&aRoasted Turkey", "rare"),
            skin("mm_skin_31_record", "RECORD_3", "&aFrisbee", "epic"),
            skin("mm_skin_32_gold_pickaxe", "GOLD_HOE", "&aFarming Implement", "epic"),
            skin("mm_skin_33_prismarine_shard", "INK_SACK", "&a10,000 Spoons", "epic"),
            skin("mm_skin_34_gold_sword", "GOLD_SWORD", "&aVIP Golden Sword", "epic"),
            skin("mm_skin_35_diamond_sword", "DIAMOND_SWORD", "&aMVP Diamond Sword", "epic"),
            skin("mm_skin_36_diamond_hoe", "DIAMOND_HOE", "&aThe Scythe", "epic"),
            skin("mm_skin_37_shears", "SHEARS", "&aShears", "epic"),
            skin("mm_skin_38_raw_fish", "RAW_FISH", "&aSalmon", "legendary"),
            skin("mm_skin_39_nether_wart", "INK_SACK", "&aRudolph's Nose", "legendary"),
            skin("mm_skin_40_bread", "BREAD", "&aFreshly Frozen Baguette", "legendary"),
            skin("mm_skin_41_boat", "BOAT", "&aEaster Basket", "legendary"),
            skin("mm_skin_42_melon", "SPECKLED_MELON", "&aGlistering Melon", "legendary"),
            skin("mm_skin_43_book", "BOOK", "&aGrimoire", "legendary"),
            skin("mm_skin_44_sapling", "SAPLING", "&aEarthen Dagger", "legendary"),
            skin("mm_skin_45_gold_axe", "GOLD_AXE", "&aShred", "legendary"),
            skin("mm_skin_46_diamond_pickaxe", "DIAMOND_PICKAXE", "&aDouble Death Scythe", "legendary"),
            skin("mm_skin_47_gold_spade", "GOLD_SPADE", "&aGold Spray Painted Shovel", "legendary")
    ));

    private final MongoCollection<Document> collection;

    public KnifeSkinStore(MongoManager mongo) {
        this.collection = mongo.getKnifeSkins();
    }

    public List<KnifeSkinDefinition> loadAll() {
        List<KnifeSkinDefinition> skins = new ArrayList<>();
        for (Document doc : collection.find()) {
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
                    .append("material", seed.material)
                    .append("displayName", seed.displayName)
                    .append("description", "")
                    .append("cost", defaultCostForRarity(rarity))
                    .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY)
                    .append(RARITY_FIELD, rarity);
            collection.updateOne(
                    eq("id", seed.id),
                    new Document("$setOnInsert", update),
                    new UpdateOptions().upsert(true)
            );
        }
        collection.updateOne(
                new Document("id", "iron_sword"),
                new Document("$set", new Document("displayName", "&aDefault Iron Sword")
                        .append("description", "Selecting this option disables your Knife Skin.")
                        .append("cost", 0)
                        .append(RARITY_FIELD, normalizeRarity(KnifeSkinDefinition.DEFAULT_RARITY))
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_02_chest"),
                new Document("$set", new Document("material", "CHEST")
                        .append("displayName", "&aRandom Knife Skin")
                        .append("description", "")
                        .append("cost", 0)
                        .append(RARITY_FIELD, "common")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_03_ender_chest"),
                new Document("$set", new Document("material", "ENDER_CHEST")
                        .append("displayName", "&aRandom Favorite Knife Skin")
                        .append("description", "")
                        .append("cost", 0)
                        .append(RARITY_FIELD, "common")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_04_iron_blade"),
                new Document("$set", new Document("material", "STONE_SWORD")
                        .append("displayName", "&aCheapo Sword")
                        .append("description", "Nothing wrong with simple living.")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_05_stick"),
                new Document("$set", new Document("material", "IRON_SPADE")
                        .append("displayName", "&aShovel")
                        .append("description", "WHACK EM.")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_08_gold_sword"),
                new Document("$set", new Document("material", "WOOD_SWORD")
                        .append("displayName", "&aStake")
                        .append("description", "Can also be used to support plants.")
                        .append(RARITY_FIELD, "common")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_06_wood_spade"),
                new Document("$set", new Document("material", "STICK")
                        .append("displayName", "&aStick")
                        .append("description", "A primitive man's weapon.")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_11_wood_spade"),
                new Document("$set", new Document("material", "STONE_SPADE")
                        .append("displayName", "&aStick with a Hat")
                        .append("description", "A gentleman's weapon.")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_07_wood_axe"),
                new Document("$set", new Document("displayName", "&aWooden Axe")
                        .append("description", "Might as well just punch the tree.")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_09_dead_bush"),
                new Document("$set", new Document("displayName", "&aChewed Up Bush")
                        .append("description", "Who's been gnawing on this poor bush?")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_10_sugar_cane"),
                new Document("$set", new Document("displayName", "&aFragile Plant")
                        .append("description", "It's more likely to split open on contact rather than do any harm.")
                        .append(RARITY_FIELD, "common")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_12_blaze_rod"),
                new Document("$set", new Document("displayName", "&aBlaze Rod")
                        .append("description", "Some say this is a blaze's spine.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_13_diamond_spade"),
                new Document("$set", new Document("displayName", "&aOnly the Best")
                        .append("description", "The most premium weapon.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_14_quartz"),
                new Document("$set", new Document("displayName", "&aJagged Knife")
                        .append("description", "Sharpened and ready.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_15_rabbit_foot"),
                new Document("$set", new Document("material", "PUMPKIN_PIE")
                        .append("displayName", "&aPumpkin Pie")
                        .append("description", "Delicious and deadly.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_16_gold_pickaxe"),
                new Document("$set", new Document("displayName", "&aGold Digger")
                        .append("description", "$$$.")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_17_leather"),
                new Document("$set", new Document("displayName", "&aUnfortunately Colored Jacket")
                        .append("description", "This thing could use a wash or two. Probably three if we're being honest.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_18_gold_spade"),
                new Document("$set", new Document("material", "NAME_TAG")
                        .append("displayName", "&aMouse Trap")
                        .append("description", "Gotta catch that rat.")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_19_coal"),
                new Document("$set", new Document("material", "COAL")
                        .append("displayName", "&aCampfire Leftovers")
                        .append("description", "A surprisingly good bug repellent.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_20_flint"),
                new Document("$set", new Document("displayName", "&aSomewhat Sharp Rock")
                        .append("description", "You could've just picked it up off the ground.")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_21_bone"),
                new Document("$set", new Document("displayName", "&aBig Bone")
                        .append("description", "Taken from one of your past victims.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_22_carrot"),
                new Document("$set", new Document("displayName", "&aRudolph's Favorite Snack")
                        .append("description", "Use as weapon, not as food.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_23_golden_carrot"),
                new Document("$set", new Document("displayName", "&aSparkly Snack")
                        .append("description", "Looks better than it tastes.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_24_cookie"),
                new Document("$set", new Document("displayName", "&aSweet Treat")
                        .append("description", "Kids will never want to visit your house again.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_25_diamond_axe"),
                new Document("$set", new Document("displayName", "&aTimber")
                        .append("description", "Cutting trees in style.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_26_rose"),
                new Document("$set", new Document("material", "DOUBLE_PLANT")
                        .append("displayName", "&aPrickly")
                        .append("description", "Quite sharp to the touch.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_27_prismarine_crystals"),
                new Document("$set", new Document("material", "PRISMARINE_SHARD")
                        .append("displayName", "&aIce Shard")
                        .append("description", "Also works great as a popsicle!")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_28_steak"),
                new Document("$set", new Document("material", "COOKED_BEEF")
                        .append("displayName", "&aGrilled Steak")
                        .append("description", "Straight off the grill, but also good for vampires.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_29_nether_brick"),
                new Document("$set", new Document("material", "NETHER_BRICK_ITEM")
                        .append("displayName", "&aBloody Brick")
                        .append("description", "This brick wasn't allowed to be a part of the yellow brick road.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_30_raw_chicken"),
                new Document("$set", new Document("material", "COOKED_CHICKEN")
                        .append("displayName", "&aRoasted Turkey")
                        .append("description", "Covered in lethal amounts of grease and oil.")
                        .append(RARITY_FIELD, "rare")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_31_record"),
                new Document("$set", new Document("material", "RECORD_3")
                        .append("displayName", "&aFrisbee")
                        .append("description", "Don't try and catch this.")
                        .append(RARITY_FIELD, "epic")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_32_gold_pickaxe"),
                new Document("$set", new Document("material", "GOLD_HOE")
                        .append("displayName", "&aFarming Implement")
                        .append("description", "It's useful for farming!")
                        .append(RARITY_FIELD, "epic")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_33_prismarine_shard"),
                new Document("$set", new Document("material", "INK_SACK")
                        .append("displayName", "&a10,000 Spoons")
                        .append("description", "Pretty ironic if you own this one.")
                        .append(RARITY_FIELD, "epic")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_34_gold_sword"),
                new Document("$set", new Document("material", "GOLD_SWORD")
                        .append("displayName", "&aVIP Golden Sword")
                        .append("description", "Show off your VIP rank with this fancy golden sword.")
                        .append(RARITY_FIELD, "epic")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_35_diamond_sword"),
                new Document("$set", new Document("material", "DIAMOND_SWORD")
                        .append("displayName", "&aMVP Diamond Sword")
                        .append("description", "Show off your MVP rank with this marvelous diamond sword.")
                        .append(RARITY_FIELD, "epic")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_36_diamond_hoe"),
                new Document("$set", new Document("material", "DIAMOND_HOE")
                        .append("displayName", "&aThe Scythe")
                        .append("description", "Slice your enemies... and hay.")
                        .append(RARITY_FIELD, "epic")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_37_shears"),
                new Document("$set", new Document("material", "SHEARS")
                        .append("displayName", "&aShears")
                        .append("description", "Not suitable for children.")
                        .append(RARITY_FIELD, "epic")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_38_raw_fish"),
                new Document("$set", new Document("material", "RAW_FISH")
                        .append("displayName", "&aSalmon")
                        .append("description", "Something smells fishy here...")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_39_nether_wart"),
                new Document("$set", new Document("material", "INK_SACK")
                        .append("displayName", "&aRudolph's Nose")
                        .append("description", "How'd you even get this?")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_40_bread"),
                new Document("$set", new Document("material", "BREAD")
                        .append("displayName", "&aFreshly Frozen Baguette")
                        .append("description", "Recently taken out of the freezer, it's still thawing.")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_41_boat"),
                new Document("$set", new Document("material", "BOAT")
                        .append("displayName", "&aEaster Basket")
                        .append("description", "Good for collecting!")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_42_melon"),
                new Document("$set", new Document("material", "SPECKLED_MELON")
                        .append("displayName", "&aGlistering Melon")
                        .append("description", "Often misspelled as \"glistening\".")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_43_book"),
                new Document("$set", new Document("material", "BOOK")
                        .append("displayName", "&aGrimoire")
                        .append("description", "Why do dark magic with it? Smack people on the head with it instead, works just as well.")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_44_sapling"),
                new Document("$set", new Document("material", "SAPLING")
                        .append("displayName", "&aEarthen Dagger")
                        .append("description", "Mother Earth's weapon of choice.")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_45_gold_axe"),
                new Document("$set", new Document("material", "GOLD_AXE")
                        .append("displayName", "&aShred")
                        .append("description", "Shred the innocents to pieces.")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_46_diamond_pickaxe"),
                new Document("$set", new Document("material", "DIAMOND_PICKAXE")
                        .append("displayName", "&aDouble Death Scythe")
                        .append("description", "Double the blades, double the reaping.")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateOne(
                new Document("id", "mm_skin_47_gold_spade"),
                new Document("$set", new Document("material", "GOLD_SPADE")
                        .append("displayName", "&aGold Spray Painted Shovel")
                        .append("description", "This Golden Shovel was spray painted gold, for some reason.")
                        .append(RARITY_FIELD, "legendary")
                        .append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateMany(
                new Document(GAME_TYPE_FIELD, new Document("$exists", false)),
                new Document("$set", new Document(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
        );
        collection.updateMany(
                new Document(RARITY_FIELD, new Document("$exists", false)),
                new Document("$set", new Document(RARITY_FIELD, normalizeRarity(KnifeSkinDefinition.DEFAULT_RARITY)))
        );
        collection.updateMany(
                new Document(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY)
                        .append(RARITY_FIELD, new Document("$not",
                                new Document("$regex", "^(common|rare|epic|legendary)$").append("$options", "i"))),
                new Document("$set", new Document(RARITY_FIELD, KnifeSkinDefinition.DEFAULT_RARITY))
        );
        updatePurchasableCostsByProgression();
    }

    private KnifeSkinDefinition fromDocument(Document doc) {
        if (doc == null) {
            return null;
        }
        String id = doc.getString("id");
        String material = doc.getString("material");
        String displayName = doc.getString("displayName");
        String description = doc.getString("description");
        Number costValue = doc.get("cost", Number.class);
        int cost = costValue == null ? 0 : costValue.intValue();
        String rarity = normalizeRarity(doc.getString(RARITY_FIELD));
        if (rarity.isEmpty()) {
            rarity = KnifeSkinDefinition.DEFAULT_RARITY;
        }
        return new KnifeSkinDefinition(id, material, displayName, description, cost, rarity);
    }

    private boolean supportsMurderMystery(Document doc) {
        if (doc == null) {
            return false;
        }
        String gameType = normalize(doc.getString(GAME_TYPE_FIELD)).replace("_", "");
        return gameType.isEmpty() || gameType.equals(GAME_TYPE_MURDER_MYSTERY);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static SeedSkin skin(String id, String material, String displayName, String rarity) {
        return new SeedSkin(id, material, displayName, rarity);
    }

    private void updateCostsByRarity(String rarity) {
        String normalized = normalizeRarity(rarity);
        int cost = defaultCostForRarity(normalized);
        Document filter = new Document(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY)
                .append(RARITY_FIELD, new Document("$regex", "^" + normalized + "$").append("$options", "i"))
                .append("id", new Document("$nin", NON_PURCHASABLE_IDS));
        collection.updateMany(filter, new Document("$set", new Document("cost", cost)));
    }

    private void updatePurchasableCostsByProgression() {
        // Common: mm_skin_04..11
        updateSequentialCosts(4, 11, 250000, 100000);
        // Rare: mm_skin_12..30 (starts at Blaze Rod)
        updateSequentialCosts(12, 30, 1500000, 175000);
        // Epic: mm_skin_31..37
        updateSequentialCosts(31, 37, 5750000, 500000);
        // Legendary: mm_skin_38..47 (starts at 9.5M)
        updateSequentialCosts(38, 47, 9500000, 500000);
    }

    private void updateSequentialCosts(int startInclusive, int endInclusive, int baseCost, int step) {
        int from = Math.max(0, startInclusive);
        int to = Math.max(from, endInclusive);
        int safeBase = Math.max(0, baseCost);
        int safeStep = Math.max(0, step);
        for (int number = from; number <= to; number++) {
            String id = "mm_skin_" + String.format(Locale.ROOT, "%02d", number) + "_" + suffixFor(number);
            int offset = number - from;
            int cost = safeBase + (offset * safeStep);
            collection.updateOne(
                    new Document("id", id),
                    new Document("$set", new Document("cost", cost).append(GAME_TYPE_FIELD, GAME_TYPE_MURDER_MYSTERY))
            );
        }
    }

    private String suffixFor(int number) {
        switch (number) {
            case 4:
                return "iron_blade";
            case 5:
                return "stick";
            case 6:
                return "wood_spade";
            case 7:
                return "wood_axe";
            case 8:
                return "gold_sword";
            case 9:
                return "dead_bush";
            case 10:
                return "sugar_cane";
            case 11:
                return "wood_spade";
            case 12:
                return "blaze_rod";
            case 13:
                return "diamond_spade";
            case 14:
                return "quartz";
            case 15:
                return "rabbit_foot";
            case 16:
                return "gold_pickaxe";
            case 17:
                return "leather";
            case 18:
                return "gold_spade";
            case 19:
                return "coal";
            case 20:
                return "flint";
            case 21:
                return "bone";
            case 22:
                return "carrot";
            case 23:
                return "golden_carrot";
            case 24:
                return "cookie";
            case 25:
                return "diamond_axe";
            case 26:
                return "rose";
            case 27:
                return "prismarine_crystals";
            case 28:
                return "steak";
            case 29:
                return "nether_brick";
            case 30:
                return "raw_chicken";
            case 31:
                return "record";
            case 32:
                return "gold_pickaxe";
            case 33:
                return "prismarine_shard";
            case 34:
                return "gold_sword";
            case 35:
                return "diamond_sword";
            case 36:
                return "diamond_hoe";
            case 37:
                return "shears";
            case 38:
                return "raw_fish";
            case 39:
                return "nether_wart";
            case 40:
                return "bread";
            case 41:
                return "boat";
            case 42:
                return "melon";
            case 43:
                return "book";
            case 44:
                return "sapling";
            case 45:
                return "gold_axe";
            case 46:
                return "diamond_pickaxe";
            case 47:
                return "gold_spade";
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
