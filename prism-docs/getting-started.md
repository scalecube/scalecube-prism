# Getting started

prism is a thin layer over a scalecube `Cluster`. You build an ordinary cluster node, wrap it once, and
a **service registry** and a **singleton elector** hang off the wrapper. This guide takes you from zero
to a working registry and a leader election in a few minutes. For the full tour see the
[user guide](user-guide.md); every snippet here is a real, runnable example in
[`prism-examples`](../prism-examples).

## 1. Add the dependency

prism is multi-module; most apps need `prism-runtime` (the wrapper) plus a transport. Add the elector,
persistence, and observability modules if you use those features.

```xml
<dependency>
  <groupId>io.scalecube</groupId>
  <artifactId>scalecube-prism-runtime</artifactId>
</dependency>
<dependency>
  <groupId>io.scalecube</groupId>
  <artifactId>scalecube-transport-netty</artifactId>
</dependency>
```

## 2. Wrap a cluster — "hello prism"

```java
Prism prism =
    new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new)).startAwait();

prism.registry().register("hello", Map.of("greeting", "world")).block();
prism.registry().lookup("hello").forEach(e -> System.out.println(e.service() + " " + e.properties()));

prism.shutdown().block(); // stops prism; the cluster's own lifecycle stays yours
```

prism **decorates** the cluster — `shutdown()` stops prism without taking ownership of the cluster.
Runnable: `HelloPrismExample`.

## 3. Register and discover across nodes

A provider advertises a service; a consumer on another node discovers it after gossip converges
(discovery is AP — a local, always-available view).

```java
provider.registry().register("orders", Map.of("weight", "100", "status", "passing")).block();
// ... on the consumer, after a round or two of gossip ...
for (ServiceEntry e : consumer.registry().lookup("orders")) {
  System.out.println(e.service() + " @ " + e.address() + " " + e.properties());
}
```

Runnable: `ServiceRegistryExample`. Treat discovery as a *hint*: retry on `ServiceUnavailable` (see
[troubleshooting](troubleshooting.md)).

## 4. Elect a single leader

The elector needs a `PrismConfig` declaring the consensus quorum (a dedicated transport). The smallest
case is a single-node quorum:

```java
String addr = InetAddress.getLocalHost().getHostAddress() + ":7101";
PrismConfig config = new PrismConfig(addr, List.of(addr), TcpTransportFactory::new);
Prism prism = new PrismImpl(new ClusterImpl().transportFactory(TcpTransportFactory::new), config)
    .startAwait();

prism.elector().leadership("gateway").subscribe(l ->
    System.out.println("active=" + l.active() + " epoch=" + l.epoch()));
prism.elector().campaign("gateway").block();
```

For a real, partition-safe 3-node election (with failover and fencing), see `PrismGatewayExample`.

## 5. Run the examples

Each example is a `public static void main`. Run from your IDE, or on the command line with the module
on the classpath, e.g.:

```
java -cp "prism-examples/target/classes;<deps>" io.scalecube.prism.examples.HelloPrismExample
```

(Build `<deps>` with `mvn -pl prism-examples dependency:build-classpath -Dmdep.outputFile=cp.txt`.)

## Where next

- The [user guide](user-guide.md) — the full feature tour, example by example.
- The [consistency dial](../README.md) and [guarantees](guarantees.md) — what each tier promises.
- [config-reference](config-reference.md) and [troubleshooting](troubleshooting.md) for operating it.
