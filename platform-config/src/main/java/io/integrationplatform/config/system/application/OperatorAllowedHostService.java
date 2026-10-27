package io.integrationplatform.config.system.application;

import java.util.List;

public interface OperatorAllowedHostService {

    List<String> list();

    List<String> add(String pattern);

    List<String> remove(String pattern);
}
