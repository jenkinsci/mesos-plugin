package org.jenkinsci.plugins.mesos.api;

import akka.Done;
import com.mesosphere.usi.repository.PodRecordRepository;
import scala.collection.Map$;
import scala.collection.immutable.Map;
import scala.concurrent.Future;
import scala.concurrent.Future$;

public class InMemoryRepository implements PodRecordRepository {

  private final Map<Object, Object> data = Map$.MODULE$.empty();

  @Override
  public Future<Done> delete(Object record) {
    return Future$.MODULE$.successful(Done.done());
  }

  @Override
  public Future<Done> store(Object record) {
    return Future$.MODULE$.successful(Done.done());
  }

  @Override
  public Future<Map<Object, Object>> readAll() {
    return Future$.MODULE$.successful(data);
  }
}
