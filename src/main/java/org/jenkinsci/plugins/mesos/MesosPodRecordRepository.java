package org.jenkinsci.plugins.mesos;

import akka.Done;
import com.mesosphere.usi.repository.PodRecordRepository;
import java.util.concurrent.CompletableFuture;
import scala.collection.immutable.HashMap;
import scala.collection.immutable.Map;
import scala.compat.java8.FutureConverters;
import scala.concurrent.Future;

public class MesosPodRecordRepository implements PodRecordRepository {
  @Override
  public Future<Done> delete(Object record) {
    CompletableFuture<Done> javaFuture = CompletableFuture.supplyAsync(() -> Done.done());
    return FutureConverters.toScala((javaFuture));
  }

  @Override
  public Future<Done> store(Object record) {
    CompletableFuture<Done> javaFuture = CompletableFuture.supplyAsync(() -> Done.done());
    return FutureConverters.toScala((javaFuture));
  }

  @Override
  public Future<Map<Object, Object>> readAll() {
    Map<Object, Object> map = new HashMap<>();
    CompletableFuture<Map<Object, Object>> javaFuture = CompletableFuture.supplyAsync(() -> map);
    return FutureConverters.toScala((javaFuture));
  }
}
