package io.scalecube.prism.registry.impl;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.ClusterMessageHandler;
import io.scalecube.cluster.Member;
import io.scalecube.cluster.transport.api.Message;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * Minimal in-memory {@link Cluster} for deterministic registry tests: {@code spreadGossip} delivers
 * synchronously to a wired peer handler, so propagation is immediate and reproducible (no network).
 */
final class FakeCluster implements Cluster {

  private final Member member;
  ClusterMessageHandler peer;

  FakeCluster(String id, String address) {
    this.member = new Member(id, null, address, "prism");
  }

  @Override
  public String address() {
    return member.address();
  }

  @Override
  public Mono<String> spreadGossip(Message message) {
    if (peer != null) {
      peer.onGossip(message);
    }
    return Mono.just("ok");
  }

  @Override
  public <T> Optional<T> metadata() {
    return Optional.empty();
  }

  @Override
  public <T> Optional<T> metadata(Member m) {
    return Optional.empty();
  }

  @Override
  public Member member() {
    return member;
  }

  @Override
  public Optional<Member> memberById(String id) {
    return member.id().equals(id) ? Optional.of(member) : Optional.empty();
  }

  @Override
  public Optional<Member> memberByAddress(String address) {
    return member.address().equals(address) ? Optional.of(member) : Optional.empty();
  }

  @Override
  public Collection<Member> members() {
    return List.of(member);
  }

  @Override
  public Collection<Member> otherMembers() {
    return List.of();
  }

  @Override
  public <T> Mono<Void> updateMetadata(T metadata) {
    return Mono.empty();
  }

  @Override
  public void shutdown() {
    // no-op
  }

  @Override
  public Mono<Void> onShutdown() {
    return Mono.empty();
  }
}
