package io.integrationplatform.t98.archfixture.planted;

import jakarta.jms.ConnectionFactory;

/** Planted T98 violation: a JMS import outside routing.adapter.mq. Never production code. */
public class PlantedJmsOutsideMqAdapter {

    ConnectionFactory factory;
}
