package com.aioj.next.problem.domain;

/** Published by ProblemCatalog after a problem is created, edited, archived, restored or deleted. */
public record ProblemChangedEvent(Long problemId) {
}
