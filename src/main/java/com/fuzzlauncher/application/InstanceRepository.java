package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.GameInstance;
import java.io.IOException;
import java.util.List;

public interface InstanceRepository {
  List<GameInstance> findAll() throws IOException;

  void save(GameInstance instance) throws IOException;

  void remove(String id) throws IOException;
}
