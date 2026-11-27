import { Router, Request, Response } from 'express';
import { db } from '../db.js';
import { UserLearningState } from '../types/index.js';

export const userRouter = Router();

// GET /api/user/learning
userRouter.get('/learning', (_req: Request, res: Response) => {
  const learningState = db.getUserLearning();
  res.json(learningState);
});

// POST /api/user/learning (전체 상태 동기화)
userRouter.post('/learning', (req: Request, res: Response) => {
  const state: Partial<UserLearningState> = req.body;
  const updated = db.updateUserLearning(state);
  res.json(updated);
});

// POST /api/user/bookmarks (북마크 토글/저장)
userRouter.post('/bookmarks', (req: Request, res: Response) => {
  const { topicId } = req.body;
  if (!topicId) {
    return res.status(400).json({ error: 'topicId가 필요합니다.' });
  }

  const current = db.getUserLearning();
  let bookmarks = current.bookmarks || [];
  if (bookmarks.includes(topicId)) {
    bookmarks = bookmarks.filter(id => id !== topicId);
  } else {
    bookmarks.push(topicId);
  }

  const updated = db.updateUserLearning({ bookmarks });
  res.json({ bookmarks: updated.bookmarks });
});

// POST /api/user/progress (완료 토글/저장)
userRouter.post('/progress', (req: Request, res: Response) => {
  const { topicId } = req.body;
  if (!topicId) {
    return res.status(400).json({ error: 'topicId가 필요합니다.' });
  }

  const current = db.getUserLearning();
  let completedTopics = current.completedTopics || [];
  if (completedTopics.includes(topicId)) {
    completedTopics = completedTopics.filter(id => id !== topicId);
  } else {
    completedTopics.push(topicId);
  }

  const updated = db.updateUserLearning({ completedTopics });
  res.json({ completedTopics: updated.completedTopics });
});

// POST /api/user/notes (메모 저장)
userRouter.post('/notes', (req: Request, res: Response) => {
  const { topicId, note } = req.body;
  if (!topicId) {
    return res.status(400).json({ error: 'topicId가 필요합니다.' });
  }

  const current = db.getUserLearning();
  const notes = {
    ...current.notes,
    [topicId]: note || ''
  };

  const updated = db.updateUserLearning({ notes });
  res.json({ notes: updated.notes });
});
