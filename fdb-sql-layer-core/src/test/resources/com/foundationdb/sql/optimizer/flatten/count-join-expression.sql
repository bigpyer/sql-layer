SELECT COUNT(*) FROM
    (SELECT concat(c1,'ice') AS val1 FROM t1) AS anon1,
    (SELECT c1 AS val2 FROM t1) AS anon2
WHERE val1 = val2
