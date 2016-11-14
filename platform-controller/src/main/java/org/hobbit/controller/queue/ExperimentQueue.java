package org.hobbit.controller.queue;

import java.util.List;

import org.hobbit.controller.data.ExperimentConfiguration;

/**
 * This is the interface of a queue containing experiment configurations that
 * should be executed.
 * 
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 */
public interface ExperimentQueue {

	/**
	 * Returns the experiment that should be executed next.
	 * 
	 * @return the experiment that should be executed next
	 */
	public ExperimentConfiguration getNextExperiment();

	/**
	 * Adds the given experiment to the queue.
	 * 
	 * @param experiment
	 *            the experiment that should be added
	 */
	public void add(ExperimentConfiguration experiment);

	/**
	 * Removes the experiment from the queue.
	 * 
	 * @param experiment
	 *            the experiment that should be removed from the queue
	 */
	public void remove(ExperimentConfiguration experiment);

	/**
	 * Returns the list of all experiments waiting in this queue.
	 * 
	 * @return the list of all experiments waiting in this queue
	 */
	public List<ExperimentConfiguration> listAll();
}
