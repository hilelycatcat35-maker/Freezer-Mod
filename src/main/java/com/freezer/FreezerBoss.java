package com.freezer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class FreezerBoss extends SnowGolem {

    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.literal("The Freezer"),
            BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarOverlay.PROGRESS);

    private record Spike(Vec3 pos, int[] delay) {}

    private final List<Snowball> iceBlocks = new ArrayList<>();
    private final List<Vec3> iceBlockLastPos = new ArrayList<>();
    private final List<Spike> spikes = new ArrayList<>();
    private final List<SnowGolem> minions = new ArrayList<>();

    private int cooldown = 80;
    private int stuckTicks = 0;
    private int axeWindup = 0;
    private int beamTicks = 0;
    private int minionTimer = 0;

    public FreezerBoss(EntityType<? extends SnowGolem> type, Level level) {
        super(type, level);
        this.setPumpkin(false);
        this.xpReward = 100;
    }

    public static AttributeSupplier.Builder createBossAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 500.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ATTACK_DAMAGE, 0.0)
                .add(Attributes.SCALE, 2.2);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 40.0F));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossEvent.removePlayer(player);
    }

    @Override
    public void remove(RemovalReason reason) {
        bossEvent.removeAllPlayers();
        super.remove(reason);
    }

    // ---------- Damage that ignores armor ----------
    private DamageSource src(ServerLevel level, Entity by) {
        return level.damageSources().indirectMagic(this, by);
    }

    private void hit(ServerLevel level, LivingEntity target, float amount) {
        target.hurtServer(level, src(level, this), amount);
    }

    private boolean isFoe(LivingEntity e) {
        return e != this && !(e instanceof SnowGolem) && e.isAlive();
    }

    // ---------- Main brain ----------
    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        bossEvent.setProgress(this.getHealth() / this.getMaxHealth());

        tickIceBlocks(level);
        tickSpikes(level);
        tickMinions(level);

        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) return;
        this.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (stuckTicks > 0) {
            stuckTicks--;
            this.getNavigation().stop();
            return;
        }
        if (axeWindup > 0) {
            this.getNavigation().stop();
            axeWindup--;
            if (axeWindup == 0) axeSmash(level);
            return;
        }
        if (beamTicks > 0) {
            this.getNavigation().stop();
            tickBeam(level, target);
            return;
        }

        double dist = this.distanceTo(target);
        if (dist > 5.0) this.getNavigation().moveTo(target, 1.0);
        else this.getNavigation().stop();

        if (--cooldown > 0) return;
        cooldown = 50 + this.random.nextInt(40);

        if (dist < 6.0 && this.random.nextInt(10) < 6) {
            axeWindup = 20;
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_PLACE, SoundSource.HOSTILE, 2f, 0.6f);
            return;
        }
        switch (this.random.nextInt(4)) {
            case 0 -> throwIceBlock(level, target);
            case 1 -> beamTicks = 30;
            case 2 -> summonSpikes(level, target);
            default -> {
                if (minions.size() < 3) summonMinions(level, target);
                else throwIceBlock(level, target);
            }
        }
    }

    // ---------- Ability 1: giant ice block (10 dmg + 4s freeze) ----------
    private void throwIceBlock(ServerLevel level, LivingEntity target) {
        Snowball block = new Snowball(level, this, new ItemStack(Items.PACKED_ICE));
        block.setPos(getX(), getEyeY(), getZ());
        Vec3 d = target.getEyePosition().subtract(block.position()).normalize();
        block.shoot(d.x, d.y, d.z, 1.5f, 0.0f);
        level.addFreshEntity(block);
        iceBlocks.add(block);
        iceBlockLastPos.add(block.position());
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.HOSTILE, 2f, 0.5f);
    }

    private void tickIceBlocks(ServerLevel level) {
        for (int i = iceBlocks.size() - 1; i >= 0; i--) {
            Snowball b = iceBlocks.get(i);
            Vec3 pos = b.isRemoved() ? iceBlockLastPos.get(i) : b.position();
            AABB box = new AABB(pos, pos).inflate(1.6);
            List<LivingEntity> hits = level.getEntitiesOfClass(LivingEntity.class, box, this::isFoe);
            if (!hits.isEmpty() || b.isRemoved()) {
                for (LivingEntity e : hits) {
                    hit(level, e, 10.0f);
                    freeze(e);
                }
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()),
                        pos.x, pos.y, pos.z, 40, 0.5, 0.5, 0.5, 0.1);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 2f, 0.7f);
                b.discard();
                iceBlocks.remove(i);
                iceBlockLastPos.remove(i);
            } else {
                iceBlockLastPos.set(i, pos);
            }
        }
    }

    private void freeze(LivingEntity e) {
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 250, false, false));
        e.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 80, 250, false, false));
        e.setTicksFrozen(e.getTicksRequiredToFreeze() + 80);
    }

    // ---------- Ability 2: axe smash (45 dmg, axe stuck for 2s) ----------
    private void axeSmash(ServerLevel level) {
        AABB box = this.getBoundingBox().inflate(5.0, 2.0, 5.0);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            hit(level, e, 45.0f);
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()),
                getX(), getY() + 0.2, getZ(), 80, 2.0, 0.2, 2.0, 0.2);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2f, 0.7f);
        stuckTicks = 40; // axe stuck in the ground for 2 seconds
    }

    // ---------- Ability 3: ice beam from the mouth (30 dmg) ----------
    private Vec3 beamDir = Vec3.ZERO;

    private void tickBeam(ServerLevel level, LivingEntity target) {
        beamTicks--;
        Vec3 eye = this.getEyePosition();
        if (beamTicks > 10) {
            level.sendParticles(ParticleTypes.SNOWFLAKE, eye.x, eye.y, eye.z, 6, 0.3, 0.3, 0.3, 0.02);
            beamDir = target.getEyePosition().subtract(eye).normalize();
            return;
        }
        if (beamTicks == 10) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 3f, 0.4f);
            List<LivingEntity> already = new ArrayList<>();
            for (double s = 1.0; s < 32.0; s += 0.5) {
                Vec3 p = eye.add(beamDir.scale(s));
                if (!level.getBlockState(BlockPos.containing(p)).isAir()) break;
                level.sendParticles(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, 3, 0.05, 0.05, 0.05, 0.0);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(p, p).inflate(0.9), this::isFoe)) {
                    if (!already.contains(e)) {
                        already.add(e);
                        hit(level, e, 30.0f);
                    }
                }
            }
        }
    }

    // ---------- Ability 4: ice spikes (5 dmg each) ----------
    private void summonSpikes(ServerLevel level, LivingEntity target) {
        Vec3 dir = new Vec3(target.getX() - getX(), 0, target.getZ() - getZ()).normalize();
        for (int i = 1; i <= 12; i++) {
            double x = getX() + dir.x * i * 1.5;
            double z = getZ() + dir.z * i * 1.5;
            double y = findGround(level, x, target.getY(), z);
            spikes.add(new Spike(new Vec3(x, y, z), new int[] { i * 2 }));
        }
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 2f, 0.6f);
    }

    private double findGround(ServerLevel level, double x, double startY, double z) {
        BlockPos pos = BlockPos.containing(x, startY + 2, z);
        for (int i = 0; i < 8 && level.getBlockState(pos).isAir(); i++) pos = pos.below();
        return pos.getY() + 1;
    }

    private void tickSpikes(ServerLevel level) {
        for (int i = spikes.size() - 1; i >= 0; i--) {
            Spike s = spikes.get(i);
            if (--s.delay()[0] > 0) continue;
            Vec3 p = s.pos();
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()),
                    p.x, p.y + 0.5, p.z, 25, 0.3, 0.6, 0.3, 0.05);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1f, 1.2f);
            AABB box = new AABB(p.x - 0.7, p.y, p.z - 0.7, p.x + 0.7, p.y + 2.0, p.z + 0.7);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
                hit(level, e, 5.0f);
            }
            spikes.remove(i);
        }
    }

    // ---------- Ability 5: small snowmen (1 dmg per snowball) + launch into the air ----------
    private void summonMinions(ServerLevel level, LivingEntity target) {
        for (int i = 0; i < 3; i++) {
            SnowGolem golem = EntityType.SNOW_GOLEM.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (golem == null) continue;
            golem.setPos(getX() + (random.nextDouble() - 0.5) * 6, getY(), getZ() + (random.nextDouble() - 0.5) * 6);
            golem.setTarget(target);
            level.addFreshEntity(golem);
            minions.add(golem);
        }
        // shockwave that throws nearby enemies into the air
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(7.0), this::isFoe)) {
            e.setDeltaMovement(e.getDeltaMovement().add(0, 1.2, 0));
            e.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.SNOWFLAKE, getX(), getY() + 1, getZ(), 80, 3, 1, 3, 0.1);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.SNOW_GOLEM_AMBIENT, SoundSource.HOSTILE, 3f, 0.5f);
    }

    private void tickMinions(ServerLevel level) {
        minions.removeIf(m -> !m.isAlive());
        if (++minionTimer < 20) return;
        minionTimer = 0;
        for (SnowGolem m : minions) {
            LivingEntity t = m.getTarget();
            if (t == null || !t.isAlive()) {
                Player p = level.getNearestPlayer(m, 24.0);
                if (p != null) m.setTarget(p);
                continue;
            }
            if (m.distanceTo(t) < 14.0 && m.hasLineOfSight(t)) {
                t.hurtServer(level, level.damageSources().indirectMagic(m, m), 1.0f);
            }
        }
    }
}
