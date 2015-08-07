A = LOAD 'src/it/resources/unsorted.csv' USING PigStorage(',') AS (f1:chararray, f2: chararray, f3: int);
B = WINDOW A RANGE 5 SECONDS SLIDE RANGE 5 SECONDS;
C = ORDER B BY f1, f2, f3;
STORE C INTO 'sorted.out';
