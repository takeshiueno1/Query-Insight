UPDATE manager_evaluations
SET weighted_score=(
      SELECT CAST(AVG(CASE d.level
        WHEN 5 THEN 100 WHEN 4 THEN 85 WHEN 3 THEN 75
        WHEN 2 THEN 65 WHEN 1 THEN 55 WHEN 0 THEN 0 END) AS DECIMAL(5,2))
      FROM manager_evaluation_details d
      WHERE d.manager_evaluation_id=manager_evaluations.id),
    grade=(
      SELECT CASE
        WHEN AVG(CASE d.level
          WHEN 5 THEN 100 WHEN 4 THEN 85 WHEN 3 THEN 75
          WHEN 2 THEN 65 WHEN 1 THEN 55 WHEN 0 THEN 0 END)>=90 THEN 'S'
        WHEN AVG(CASE d.level
          WHEN 5 THEN 100 WHEN 4 THEN 85 WHEN 3 THEN 75
          WHEN 2 THEN 65 WHEN 1 THEN 55 WHEN 0 THEN 0 END)>=80 THEN 'A'
        WHEN AVG(CASE d.level
          WHEN 5 THEN 100 WHEN 4 THEN 85 WHEN 3 THEN 75
          WHEN 2 THEN 65 WHEN 1 THEN 55 WHEN 0 THEN 0 END)>=70 THEN 'B'
        WHEN AVG(CASE d.level
          WHEN 5 THEN 100 WHEN 4 THEN 85 WHEN 3 THEN 75
          WHEN 2 THEN 65 WHEN 1 THEN 55 WHEN 0 THEN 0 END)>=60 THEN 'C'
        WHEN AVG(CASE d.level
          WHEN 5 THEN 100 WHEN 4 THEN 85 WHEN 3 THEN 75
          WHEN 2 THEN 65 WHEN 1 THEN 55 WHEN 0 THEN 0 END)>=50 THEN 'D'
        ELSE 'F' END
      FROM manager_evaluation_details d
      WHERE d.manager_evaluation_id=manager_evaluations.id)
WHERE EXISTS (
  SELECT 1 FROM manager_evaluation_details d
  WHERE d.manager_evaluation_id=manager_evaluations.id);

UPDATE evaluation_targets
SET final_score=(
      SELECT m.weighted_score FROM manager_evaluations m
      WHERE m.id=evaluation_targets.current_manager_evaluation_id),
    final_grade=(
      SELECT m.grade FROM manager_evaluations m
      WHERE m.id=evaluation_targets.current_manager_evaluation_id)
WHERE current_manager_evaluation_id IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM manager_evaluation_details d
    WHERE d.manager_evaluation_id=evaluation_targets.current_manager_evaluation_id);
