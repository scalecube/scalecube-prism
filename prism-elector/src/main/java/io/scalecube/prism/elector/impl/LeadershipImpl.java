package io.scalecube.prism.elector.impl;

import io.scalecube.cluster.Member;
import io.scalecube.prism.elector.Leadership;

/** Immutable {@link Leadership} state change. */
final class LeadershipImpl implements Leadership {

  private final String group;
  private final Member member;
  private final long epoch;
  private final boolean active;

  LeadershipImpl(String group, Member member, long epoch, boolean active) {
    this.group = group;
    this.member = member;
    this.epoch = epoch;
    this.active = active;
  }

  @Override
  public String group() {
    return group;
  }

  @Override
  public Member member() {
    return member;
  }

  @Override
  public long epoch() {
    return epoch;
  }

  @Override
  public boolean active() {
    return active;
  }

  @Override
  public String toString() {
    return "Leadership{group=" + group + ", member=" + member + ", epoch=" + epoch + ", active="
        + active + '}';
  }
}
