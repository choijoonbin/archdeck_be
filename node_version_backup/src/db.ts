import fs from 'fs';
import path from 'path';
import { DatabaseSchema, Topic, GlossaryTerm, ComparisonMatrix, ChecklistItem, UserLearningState } from './types/index.js';
import { initialTopics, initialGlossaryTerms, initialComparisons, initialChecklists, initialUserLearning } from './initialData.js';

const DATA_DIR = path.resolve(process.cwd(), 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');

class Database {
  private data: DatabaseSchema;

  constructor() {
    this.ensureDataDirectory();
    this.data = this.loadDatabase();
  }

  private ensureDataDirectory() {
    if (!fs.existsSync(DATA_DIR)) {
      fs.mkdirSync(DATA_DIR, { recursive: true });
    }
  }

  private loadDatabase(): DatabaseSchema {
    if (fs.existsSync(DB_FILE)) {
      try {
        const content = fs.readFileSync(DB_FILE, 'utf-8');
        return JSON.parse(content);
      } catch (err) {
        console.warn('⚠️ db.json 로드 실패, 초기 데이터로 복구합니다:', err);
      }
    }

    const defaultData: DatabaseSchema = {
      topics: initialTopics,
      comparisons: initialComparisons,
      glossary: initialGlossaryTerms,
      checklists: initialChecklists,
      userLearning: initialUserLearning
    };

    this.saveDatabase(defaultData);
    return defaultData;
  }

  private saveDatabase(data: DatabaseSchema) {
    try {
      const tempFile = `${DB_FILE}.tmp`;
      fs.writeFileSync(tempFile, JSON.stringify(data, null, 2), 'utf-8');
      fs.renameSync(tempFile, DB_FILE);
    } catch (err) {
      console.error('❌ 데이터베이스 저장 실패:', err);
    }
  }

  // Topics
  getTopics(): Topic[] {
    return this.data.topics;
  }

  getTopicById(id: string): Topic | undefined {
    return this.data.topics.find(t => t.id === id || t.slug === id);
  }

  addTopic(topic: Topic): Topic {
    const existingIndex = this.data.topics.findIndex(t => t.id === topic.id);
    if (existingIndex >= 0) {
      this.data.topics[existingIndex] = topic;
    } else {
      this.data.topics.unshift(topic);
    }
    this.saveDatabase(this.data);
    return topic;
  }

  // Comparisons
  getComparisons(): ComparisonMatrix[] {
    return this.data.comparisons;
  }

  getComparisonById(id: string): ComparisonMatrix | undefined {
    return this.data.comparisons.find(c => c.id === id);
  }

  // Glossary
  getGlossary(): GlossaryTerm[] {
    return this.data.glossary;
  }

  getGlossaryById(id: string): GlossaryTerm | undefined {
    return this.data.glossary.find(g => g.id === id);
  }

  addGlossary(term: GlossaryTerm): GlossaryTerm {
    const existingIndex = this.data.glossary.findIndex(g => g.id === term.id);
    if (existingIndex >= 0) {
      this.data.glossary[existingIndex] = term;
    } else {
      this.data.glossary.unshift(term);
    }
    this.saveDatabase(this.data);
    return term;
  }

  // Checklists
  getChecklists(): ChecklistItem[] {
    return this.data.checklists;
  }

  // User Learning State
  getUserLearning(): UserLearningState {
    return this.data.userLearning;
  }

  updateUserLearning(state: Partial<UserLearningState>): UserLearningState {
    this.data.userLearning = {
      ...this.data.userLearning,
      ...state
    };
    this.saveDatabase(this.data);
    return this.data.userLearning;
  }

  // Re-seed utility
  resetToInitial(): void {
    const defaultData: DatabaseSchema = {
      topics: initialTopics,
      comparisons: initialComparisons,
      glossary: initialGlossaryTerms,
      checklists: initialChecklists,
      userLearning: initialUserLearning
    };
    this.data = defaultData;
    this.saveDatabase(defaultData);
  }
}

export const db = new Database();
